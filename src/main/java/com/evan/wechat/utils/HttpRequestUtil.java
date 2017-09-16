package com.evan.wechat.utils;


import com.alibaba.fastjson.JSON;
import org.apache.commons.httpclient.ConnectTimeoutException;
import org.apache.commons.httpclient.NoHttpResponseException;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpRequestRetryHandler;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.HttpHostConnectException;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.LayeredConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.UnknownHostException;

public class HttpRequestUtil {

    private static Logger logger = LoggerFactory.getLogger(HttpRequestUtil.class);

    private static final int TIMEOUT = 60 * 1000;

    private static CloseableHttpClient httpClient = null;

    private final static Object syncLock = new Object();

    // 设置超时
    private static void config(HttpRequestBase httpRequestBase) {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(TIMEOUT)
                .setConnectTimeout(TIMEOUT)
                .setSocketTimeout(TIMEOUT)
                .build();
        httpRequestBase.setConfig(requestConfig);
    }

    /**
     * 获取httpClient
     *
     * @param url
     * @return
     */
    public static CloseableHttpClient getHttpClient(String url) {
        String hostname = url.split("/")[2];
        int port = 80;
        if (hostname.contains(":")) {
            String[] arr = hostname.split(":");
            hostname = arr[0];
            port = Integer.parseInt(arr[1]);
        }
        if (httpClient == null) {
            synchronized (syncLock) {
                if (httpClient == null) {
                    httpClient = createHttpClient(200, 40, 100, hostname, port);
                }
            }
        }
        return httpClient;
    }

    private static CloseableHttpClient createHttpClient(int maxTotal, int maxPerRoute, int maxRoute, String hostname, int port) {
        ConnectionSocketFactory plainsf = PlainConnectionSocketFactory
                .getSocketFactory();
        LayeredConnectionSocketFactory sslsf = SSLConnectionSocketFactory
                .getSocketFactory();
        Registry<ConnectionSocketFactory> registry = RegistryBuilder
                .<ConnectionSocketFactory>create()
                .register("http", plainsf)
                .register("https", sslsf)
                .build();

        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager(
                registry);

        // 将最大连接数增加
        cm.setMaxTotal(maxTotal);
        // 将每个路由基础的连接增加
        cm.setDefaultMaxPerRoute(maxPerRoute);

        // 将目标主机的最大连接数增加
        HttpHost httpHost = new HttpHost(hostname, port);
        cm.setMaxPerRoute(new HttpRoute(httpHost), maxRoute);

        // 请求重试处理
        HttpRequestRetryHandler httpRequestRetryHandler = (exception, executionCount, context) -> {
            if (executionCount >= 5) {// 如果已经重试了5次，就放弃
                return false;
            }
            if (exception instanceof NoHttpResponseException) {// 如果服务器丢掉了连接，那么就重试
                return true;
            }
            if (exception instanceof SSLHandshakeException) {// 不要重试SSL握手异常
                return false;
            }
            if (exception instanceof InterruptedIOException) {// 超时
                return false;
            }
            if (exception instanceof UnknownHostException) {// 目标服务器不可达
                return false;
            }
            if (exception instanceof ConnectTimeoutException) {// 连接被拒绝
                return false;
            }
            if (exception instanceof SSLException) {// SSL握手异常
                return false;
            }

            HttpClientContext clientContext = HttpClientContext.adapt(context);
            HttpRequest request = clientContext.getRequest();
            // 如果请求是幂等的，就再次尝试
            if (!(request instanceof HttpEntityEnclosingRequest)) {
                return true;
            }
            return false;
        };

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(cm)
                .setRetryHandler(httpRequestRetryHandler)
                .build();

        return httpClient;
    }

    public static <T> T doPost(String url, String json, Class<T> clz) throws Exception {
        String result = doPost(url, json);
        return JSON.parseObject(result, clz);
    }

    public static String doPost(String url, String json) throws Exception {
        try {
            CloseableHttpClient client = getHttpClient(url);
            HttpPost post = new HttpPost(url);
            config(post);
            logger.info("====> Executing request: " + post.getRequestLine());
            if (!StringUtils.isEmpty(json)) {
                StringEntity s = new StringEntity(json, "UTF-8");
                s.setContentEncoding("UTF-8");
                s.setContentType("application/json");
                post.setEntity(s);
            }
            String responseBody = client.execute(post, getStringResponseHandler());
            logger.info("====> Getting response from request " + post.getRequestLine() + " The responseBody: " + responseBody);
            return responseBody;
        } catch (Exception e) {
            if (e instanceof HttpHostConnectException || e.getCause() instanceof ConnectException) {
                throw new ConnectException("====> 连接服务器" + url + "失败： " + e.getMessage());
            }
            logger.error("====> HttpRequestUtil.doPost: " + e.getMessage(), e);
        }
        return null;
    }

    private static ResponseHandler<String> getStringResponseHandler() {
        return response -> {
            int status = response.getStatusLine().getStatusCode();
            if (status >= 200 && status < 300) {
                HttpEntity entity = response.getEntity();
                return entity != null ? EntityUtils.toString(entity, "UTF-8") : null;
            } else {
                throw new ClientProtocolException("Unexpected response status: " + status);
            }
        };
    }

    public static <T> T doGet(String url, Class<T> clz) throws Exception {
        String result = doGet(url);
        return JSON.parseObject(result, clz);
    }

    public static String doGet(String url) throws Exception {
        try {
            CloseableHttpClient client = getHttpClient(url);
            HttpGet httpget = new HttpGet(url);
            config(httpget);
            logger.info("====> Executing request: " + httpget.getRequestLine());
            String responseBody = client.execute(httpget, getStringResponseHandler());
            logger.info("====> Getting response from request " + httpget.getRequestLine() + " The responseBody: " + responseBody);
            return responseBody;
        } catch (Exception e) {
            if (e instanceof HttpHostConnectException || e.getCause() instanceof ConnectException) {
                throw e;
            }
            logger.error("HttpRequestUtil.doGet: " + e.getMessage());
        }
        return null;
    }

}
