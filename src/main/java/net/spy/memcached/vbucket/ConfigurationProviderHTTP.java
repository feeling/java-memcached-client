/**
 * Copyright (C) 2009-2011 Couchbase, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALING
 * IN THE SOFTWARE.
 */

package net.spy.memcached.vbucket;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;

import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;

import java.text.ParseException;


import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.spy.memcached.AddrUtil;
import net.spy.memcached.compat.SpyObject;
import net.spy.memcached.vbucket.config.Bucket;
import net.spy.memcached.vbucket.config.Config;
import net.spy.memcached.vbucket.config.ConfigurationParser;
import net.spy.memcached.vbucket.config.ConfigurationParserJSON;
import net.spy.memcached.vbucket.config.Pool;

import org.apache.commons.codec.binary.Base64;

/**
 * A configuration provider.
 */
public class ConfigurationProviderHTTP extends SpyObject implements
    ConfigurationProvider {
  /**
   * Configuration management class that provides methods for retrieving vbucket
   * configuration and receiving configuration updates.
   */
  private static final String DEFAULT_POOL_NAME = "default";
  private static final String ANONYMOUS_AUTH_BUCKET = "default";
  /**
   * The specification version which this client meets. This will be included in
   * requests to the server.
   */
  public static final String CLIENT_SPEC_VER = "1.0";
  private List<URI> baseList;
  private String restUsr;
  private String restPwd;
  private URI loadedBaseUri;
  // map of <bucketname, bucket> currently loaded
  private Map<String, Bucket> buckets = new ConcurrentHashMap<String, Bucket>();

  // map of <poolname, pool> currently loaded
  // private Map<String, Pool> pools = new ConcurrentHashMap<String, Pool>();
  private ConfigurationParser configurationParser =
      new ConfigurationParserJSON();
  private Map<String, BucketMonitor> monitors =
      new HashMap<String, BucketMonitor>();

  /**
   * Constructs a configuration provider with disabled authentication for the
   * REST service.
   *
   * @param baseList list of urls to treat as base
   * @throws IOException
   */
  public ConfigurationProviderHTTP(List<URI> baseList) throws IOException {
    this(baseList, null, null);
  }

  /**
   * Constructs a configuration provider with a given credentials for the REST
   * service.
   *
   * @param baseList list of urls to treat as base
   * @param restUsr username
   * @param restPwd password
   * @throws IOException
   */
  public ConfigurationProviderHTTP(List<URI> baseList, String restUsr,
      String restPwd) throws IOException {
    this.baseList = baseList;
    this.restUsr = restUsr;
    this.restPwd = restPwd;
  }

  /**
   * Connects to the REST service and retrieves the bucket configuration from
   * the first pool available.
   *
   * @param bucketname bucketname
   * @return vbucket configuration
   */
  public Bucket getBucketConfiguration(final String bucketname) {
    if (bucketname == null || bucketname.isEmpty()) {
      throw new IllegalArgumentException("Bucket name can not be blank.");
    }
    Bucket bucket = this.buckets.get(bucketname);
    if (bucket == null) {
      readPools(bucketname);
    }
    return this.buckets.get(bucketname);
  }

  /**
   * For a given bucket to be found, walk the URIs in the baselist until the
   * bucket needed is found.
   *
   * @param bucketToFind
   */
  private void readPools(String bucketToFind) {
    // the intent with this method is to encapsulate all of the walking of URIs
    // and populating an internal object model of the configuration to one place
    for (URI baseUri : baseList) {
      try {
        // get and parse the response from the current base uri
        URLConnection baseConnection = urlConnBuilder(null, baseUri);
        String base = readToString(baseConnection);
        if ("".equals(base)) {
          getLogger().warn("Provided URI " + baseUri + " has an empty"
            + " response... skipping");
          continue;
        }
        Map<String, Pool> pools = this.configurationParser.parseBase(base);

        // check for the default pool name
        if (!pools.containsKey(DEFAULT_POOL_NAME)) {
          getLogger().warn("Provided URI " + baseUri + " has no default pool"
            + "... skipping");
          continue;
        }
        // load pools
        for (Pool pool : pools.values()) {
          URLConnection poolConnection = urlConnBuilder(baseUri, pool.getUri());
          String poolString = readToString(poolConnection);
          configurationParser.loadPool(pool, poolString);
          URLConnection poolBucketsConnection = urlConnBuilder(baseUri,
            pool.getBucketsUri());
          String sBuckets = readToString(poolBucketsConnection);
          Map<String, Bucket> bucketsForPool =
              configurationParser.parseBuckets(sBuckets);
          pool.replaceBuckets(bucketsForPool);

        }
                // did we find our bucket?
        boolean bucketFound = false;
        for (Pool pool : pools.values()) {
          if (pool.hasBucket(bucketToFind)) {
            bucketFound = true;
            break;
          }
        }
        if (bucketFound) {
          for (Pool pool : pools.values()) {
            for (Map.Entry<String, Bucket> bucketEntry : pool.getROBuckets()
                .entrySet()) {
              this.buckets.put(bucketEntry.getKey(), bucketEntry.getValue());
            }
          }
          this.loadedBaseUri = baseUri;
          return;
        }
      } catch (ParseException e) {
        getLogger().warn("Provided URI " + baseUri
          + " has an unparsable response...skipping", e);
        continue;
      } catch (IOException e) {
        getLogger().warn("Connection problems with URI " + baseUri
          + " ...skipping", e);
        continue;
      }
      throw new ConfigurationException("Configuration for bucket "
          + bucketToFind + " was not found.");
    }
  }

  public List<InetSocketAddress> getServerList(final String bucketname) {
    Bucket bucket = getBucketConfiguration(bucketname);
    List<String> servers = bucket.getConfig().getServers();
    StringBuilder serversString = new StringBuilder();
    for (String server : servers) {
      serversString.append(server).append(' ');
    }
    return AddrUtil.getAddresses(serversString.toString());
  }

  /**
   * Subscribes for configuration updates.
   *
   * @param bucketName bucket name to receive configuration for
   * @param rec reconfigurable that will receive updates
   */
  public void subscribe(String bucketName, Reconfigurable rec) {
    Bucket bucket = getBucketConfiguration(bucketName);

    ReconfigurableObserver obs = new ReconfigurableObserver(rec);
    BucketMonitor monitor = this.monitors.get(bucketName);
    if (monitor == null) {
      URI streamingURI = bucket.getStreamingURI();
      monitor = new BucketMonitor(this.loadedBaseUri.resolve(streamingURI),
        bucketName, this.restUsr, this.restPwd, configurationParser);
      this.monitors.put(bucketName, monitor);
      monitor.addObserver(obs);
      monitor.startMonitor();
    } else {
      monitor.addObserver(obs);
    }
  }

  /**
   * Unsubscribe from updates on a given bucket and given reconfigurable.
   *
   * @param vbucketName bucket name
   * @param rec reconfigurable
   */
  public void unsubscribe(String vbucketName, Reconfigurable rec) {
    BucketMonitor monitor = this.monitors.get(vbucketName);
    if (monitor != null) {
      monitor.deleteObserver(new ReconfigurableObserver(rec));
    }
  }

  public Config getLatestConfig(String bucketname) {
    Bucket bucket = getBucketConfiguration(bucketname);
    return bucket.getConfig();
  }

  public String getAnonymousAuthBucket() {
    return ANONYMOUS_AUTH_BUCKET;
  }

  /**
   * Shutdowns a monitor connections to the REST service.
   */
  public void shutdown() {
    for (BucketMonitor monitor : this.monitors.values()) {
      monitor.shutdown();
    }
  }

  /**
   * Create a URL which has the appropriate headers to interact with the
   * service. Most exception handling is up to the caller.
   *
   * @param resource the URI either absolute or relative to the base for this
   * ClientManager
   * @return
   * @throws java.io.IOException
   */
  private URLConnection urlConnBuilder(URI base, URI resource)
    throws IOException {
    if (!resource.isAbsolute() && base != null) {
      resource = base.resolve(resource);
    }
    URL specURL = resource.toURL();
    URLConnection connection = specURL.openConnection();
    connection.setRequestProperty("Accept", "application/json");
    connection.setRequestProperty("user-agent", "spymemcached vbucket client");
    connection.setRequestProperty("X-memcachekv-Store-Client-"
      + "Specification-Version", CLIENT_SPEC_VER);
    if (restUsr != null) {
      try {
        connection.setRequestProperty("Authorization",
            buildAuthHeader(restUsr, restPwd));
      } catch (UnsupportedEncodingException ex) {
        throw new IOException("Could not encode specified credentials for "
          + "HTTP request.", ex);
      }
    }
    return connection;
  }

  /**
   * Helper method that reads content from URLConnection to the string.
   *
   * @param connection a given URLConnection
   * @return content string
   * @throws IOException
   */
  private String readToString(URLConnection connection) throws IOException {
    BufferedReader reader = null;
    try {
      InputStream inStream = connection.getInputStream();
      if (connection instanceof java.net.HttpURLConnection) {
        HttpURLConnection httpConnection = (HttpURLConnection) connection;
        if (httpConnection.getResponseCode() == 403) {
          throw new IOException("Service does not accept the authentication "
            + "credentials: " + httpConnection.getResponseCode()
            + httpConnection.getResponseMessage());
        } else if (httpConnection.getResponseCode() >= 400) {
          throw new IOException("Service responded with a failure code: "
            + httpConnection.getResponseCode()
            + httpConnection.getResponseMessage());
        }
      } else {
        throw new IOException("Unexpected URI type encountered");
      }
      reader = new BufferedReader(new InputStreamReader(inStream));
      String str;
      StringBuilder buffer = new StringBuilder();
      while ((str = reader.readLine()) != null) {
        buffer.append(str);
      }
      return buffer.toString();
    } finally {
      if (reader != null) {
        reader.close();
      }
    }
  }

  /**
   * Oddly, lots of things that do HTTP seem to not know how to do this and
   * Authenticator caches for the process. Since we only need Basic at the
   * moment simply, add the header.
   *
   * @return a value for an HTTP Basic Auth Header
   */
  protected static String buildAuthHeader(String username, String password)
    throws UnsupportedEncodingException {
    // apparently netty isn't familiar with HTTP Basic Auth
    StringBuilder clearText = new StringBuilder(username);
    clearText.append(':');
    if (password != null) {
      clearText.append(password);
    }
    String headerResult;
    headerResult ="Basic "
      + Base64.encodeBase64String(clearText.toString().getBytes("UTF-8"));

    if (headerResult.endsWith("\r\n")) {
      headerResult = headerResult.substring(0, headerResult.length() - 2);
    }
    return headerResult;
  }
}
