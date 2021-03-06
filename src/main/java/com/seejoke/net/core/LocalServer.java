package com.seejoke.net.core;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.seejoke.net.conf.Constants;
import com.seejoke.net.utils.HttpClientUtils;
import com.seejoke.net.utils.Response;
import io.socket.client.IO;
import io.socket.client.IO.Options;
import io.socket.client.Socket;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.log4j.Logger;
import org.json.JSONException;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TimerTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @author yangzhongying
 * @date 2020/4/17 19:05
 * @see com.seejoke.net.core.LocalServer
 **/
public class LocalServer {

    private static ScheduledExecutorService pool = new ScheduledThreadPoolExecutor(5, new BasicThreadFactory.Builder().namingPattern("schedule-pool-%d").daemon(true).build());

    private static final String HTTP_REQUEST = "httpRequest";

    private static final String HTTP_RESPONSE = "httpResponse";

    private static final String BIND_DOMAIN = "bindDomain";

    private static final String BIND_DOMAIN_NOTICE = "bindDomainNotice";

    private static final String PING = "clientPing";

    private static final String PING_NOTICE = "clientPingNotice";

    private Logger logger = Logger.getLogger(getClass());


    private Socket socket;

    private String server;

    private String forward;

    private String domain;

    private String token;

    private String version;

    /**
     * 流量统计
     */
    private long traffic = 0;

    /**
     * 实时网速统计
     */
    private long speed = 0;

    public void setToken(String token) {
        this.token = token;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public void setForward(String forward) {
        this.forward = forward;
    }

    public void setServer(String server) {
        this.server = server;
    }

    private CallListener callListener;

    public void setCallListener(CallListener callListener) {
        this.callListener = callListener;
    }

    private void ping() {
        if (socket == null) {
            return;
        }
        socket.emit(PING, System.currentTimeMillis());
    }

    private void bindDomain() {
        // 启动绑定参数
        Map<String, String> map = new HashMap<>(16);
        map.put("domain", this.trimString(domain));
        map.put("version", this.trimString(version));
        map.put("token", this.trimString(token));
        map.put("forward", this.trimString(forward));
        String content = JSON.toJSONString(map);
        logger.debug(content);
        socket.emit(BIND_DOMAIN, content);
    }

    private String trimString(String param) {
        return param == null ? null : param.trim();
    }

    @SuppressWarnings("AlibabaAvoidUseTimer")
    public void start() throws Exception {

        Options opts = new Options();
        opts.transports = new String[]{"websocket", "polling"};
        socket = IO.socket(server, opts);

        Map<String, String> eventMapper = new HashMap<>(16);
        eventMapper.put(Socket.EVENT_DISCONNECT, "断开连接");
        eventMapper.put(Socket.EVENT_ERROR, "断开错误");
        eventMapper.put(Socket.EVENT_CONNECTING, "正在连接服务器");
        eventMapper.put(Socket.EVENT_CONNECT_TIMEOUT, "连接服务器超时");
        eventMapper.put(Socket.EVENT_RECONNECTING, "自动重连服务器");
        eventMapper.put(Socket.EVENT_RECONNECT, "准备重连服务器");
        eventMapper.put(Socket.EVENT_CONNECT, "连接服务器成功");
        eventMapper.put(HTTP_REQUEST, null);
        eventMapper.put(BIND_DOMAIN_NOTICE, null);
        eventMapper.put(PING_NOTICE, null);
        Set<String> keys = eventMapper.keySet();
        // 注册事件和提示信息
        for (String k : keys) {
            AbstractSocketListener socketListener = new AbstractSocketListener() {

                @Override
                public void eventCall(String eventName, String message, Object... args) {

                    if (null != message) {
                        callListener.eventCall("[" + eventName + "]：" + message);
                    }
                    switch (eventName) {
                        case Socket.EVENT_CONNECT:
                            callListener.statusCall("连接服务器成功");
                            LocalServer.this.bindDomain();
                            LocalServer.this.ping();
                            break;
                        case HTTP_REQUEST:
                            handlerRequest(args);
                            break;
                        case BIND_DOMAIN_NOTICE:
                            handlerBindDomain(args);
                            break;
                        case PING_NOTICE:
                            handlerPing(args);
                            break;
                        default:
                            break;
                    }
                }

            };
            socketListener.setEventName(k);
            socketListener.setMessage(eventMapper.get(k));
            socket.on(k, socketListener);
        }
        socket.connect();

        // 注册回调事件，实时统计网络速率
        TimerTask task = new TimerTask() {

            @Override
            public void run() {
                long temp = speed;
                speed = 0;
                callListener.speedCall(temp);
            }
        };
        pool.scheduleAtFixedRate(task, 0, 1, TimeUnit.SECONDS);
        pool.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                ping();
            }
        }, 0, 10000, TimeUnit.SECONDS);
    }

    private void handlerPing(Object[] args) {
        long time = (long) args[0];
        long ms = System.currentTimeMillis() - time;
        this.callListener.ping(ms);
    }

    private void handlerBindDomain(Object... args) {

        try {
            org.json.JSONObject jsonObject = (org.json.JSONObject) args[0];
            int code = jsonObject.getInt("code");
            logger.debug(code);
            String msg = jsonObject.getString("msg");
            if (code == Constants.SUCCESS_CODE) {
                // 弹出地址
                logger.debug("success");
            } else {
                // 断开链接
                socket.close();
                callListener.onClose();
            }
            this.callListener.eventCall("[绑定域名]:" + msg);
        } catch (Exception e) {
            logger.error("Exception", e);
        }
    }

    /**
     * 处理远程的http请求
     *
     * @param args 参数
     */
    private void handlerRequest(Object... args) {

        org.json.JSONObject object = (org.json.JSONObject) args[0];
        JSONObject request = JSON.parseObject(object.toString());
        String url = request.getString("url");
        Map<String, Object> headers = request.getJSONObject("headers");
        headers.remove("content-length");
        String method = request.getString("method");
        String eventName = request.getString("eventName");

        Map<String, Object> params = request.getJSONObject("params");

        String reqUrl = forward + url;
        logger.info("收到请求:methon=" + method + " url=" + reqUrl);
        if (callListener != null) {
            callListener.eventCall("[远程请求]: methon=" + method + " url=" + reqUrl + " params:" + params + " headers:" + headers);
        }
        Response response = null;
        // 发送请求
        Set<String> keys = headers.keySet();
        String encoding = "utf-8";
        if (HttpPost.METHOD_NAME.equals(method.toUpperCase())) {
            String contentType = null;
            if (headers.get(Constants.CONTENT_TYPE) != null) {
                contentType = headers.get(Constants.CONTENT_TYPE).toString();
                String[] array = contentType.split(";");
                contentType = array[0];
                if (array.length > 1) {
                    String[] charsets = array[1].split("=");
                    if (charsets.length > 1) {
                        encoding = charsets[1];
                    }
                }
            }
            HttpPost post = new HttpPost(reqUrl);
            for (String k : keys) {
                post.addHeader(k, String.valueOf(headers.get(k)));
            }
            try {
                // 默认类型application/x-www-form-urlencoded
                if (contentType == null) {
                    contentType = Constants.APPLICATION_X_WWW_FORM_URLENCODED;
                }
                if (Constants.APPLICATION_X_WWW_FORM_URLENCODED.equals(contentType)) {
                    response = HttpClientUtils.post(post, request.getJSONObject("body"));
                } else {
                    //其他的全部postBody
                    response = HttpClientUtils.postBody(post, request.getString("body"), encoding);
                }
            } catch (Exception e) {
                response = new Response();
                response.setStatusCode(500);
                response.setStatusMessage("本地服务器报错：" + e.getMessage());
            }

        } else if (HttpGet.METHOD_NAME.equals(method.toUpperCase())) {
            HttpGet get = new HttpGet(reqUrl);
            for (String k : keys) {
                get.addHeader(k, String.valueOf(headers.get(k)));
            }
            try {
                response = HttpClientUtils.get(get);
            } catch (Exception e) {
                response = new Response();
                response.setStatusCode(500);
                response.setStatusMessage("本地服务器报错：" + e.getMessage());
            }
        } else if (HttpDelete.METHOD_NAME.equals(method.toUpperCase())) {
            HttpDelete get = new HttpDelete(reqUrl);
            for (String k : keys) {
                get.addHeader(k, String.valueOf(headers.get(k)));
            }
            try {
                response = HttpClientUtils.delete(get);
            } catch (Exception e) {
                response = new Response();
                response.setStatusCode(500);
                response.setStatusMessage("本地服务器报错：" + e.getMessage());
            }
        } else if (HttpPut.METHOD_NAME.equals(method.toUpperCase())) {
            HttpPut get = new HttpPut(reqUrl);
            for (String k : keys) {
                get.addHeader(k, String.valueOf(headers.get(k)));
            }
            String contentType = "";
            if (headers.get(Constants.CONTENT_TYPE) != null) {
                logger.debug("修改header相关信息");
                contentType = headers.get(Constants.CONTENT_TYPE).toString();
                String[] array = contentType.split(";");
                contentType = array[0];
                if (array.length > 1) {
                    String[] charsets = array[1].split("=");
                    if (charsets.length > 1) {
                        encoding = charsets[1];
                    }
                }
            }
            logger.debug("接收到的类型" + contentType);
            if (contentType == null) {
                contentType = Constants.APPLICATION_X_WWW_FORM_URLENCODED;
            }
            if (Constants.APPLICATION_X_WWW_FORM_URLENCODED.equals(contentType)) {
                response = HttpClientUtils.put(get, request.getJSONObject("body"));
            } else {
                String content = request.getString("body");
                response = HttpClientUtils.putJson(get, content);
            }
            String content = JSON.toJSONString(response);
            logger.debug(content);
        } else {
            // 提示请求不支持
            response = new Response();
            response.setStatusCode(500);
            response.setEncoding("utf-8");
            response.setStatusMessage(method + "请求类型暂时不支持！");
        }
        org.json.JSONObject jsonObject = new org.json.JSONObject();
        try {
            // 处理重定向
            if (response.getStatusCode() == HttpStatus.SC_MOVED_TEMPORARILY
                    || response.getStatusCode() == HttpStatus.SC_TEMPORARY_REDIRECT
                    || response.getStatusCode() == HttpStatus.SC_SEE_OTHER) {
                // 处理地址
                String localtion = response.getHeaders().get("Location");
                logger.debug(localtion);
                localtion.replace(server, domain);
                response.getHeaders().put("Location", localtion);
            }
            byte[] bytes = (byte[]) response.getBody();
            if (bytes == null) {
                bytes = new byte[]{};
            }
            long length = bytes.length;
            this.traffic += length;
            this.speed += length;
            jsonObject.put("body", response.getBody());
            jsonObject.put("headers", response.getHeaders());
            jsonObject.put("statusCode", response.getStatusCode());
            jsonObject.put("encoding", response.getEncoding());
            jsonObject.put("statusMessage", response.getStatusMessage());
            jsonObject.put("eventName", eventName);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        logger.debug("请求响应：" + response);
        if (callListener != null) {
            callListener.eventCall("[目标响应]:" + response);
        }
        if (socket == null) {
            callListener.eventCall("[服务关闭] The service has been closed.");
            return;
        }
        socket.emit(HTTP_RESPONSE, jsonObject);
        // 通知界面显示流量
        callListener.trafficCall(this.traffic);
    }

    public void stop() {
        if (socket != null) {
            socket.close();
            socket = null;
        }
        callListener.ping(0L);
    }
}
