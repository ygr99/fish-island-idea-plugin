package com.github.yuyuanweb.mianshiyaplugin.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 摸鱼室聊天服务
 * 参考VSCode插件实现
 */
public class FishChatService {
    private static final Logger LOG = Logger.getInstance(FishChatService.class);
    private static final String API_BASE_URL = "https://api.yucoder.cn";
    private static final String WS_URL = "wss://api.yucoder.cn/ws/";
    
    private final String token;
    private WebSocket webSocket;
    private final Gson gson = new Gson();
    private UserInfo userInfo;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final List<Consumer<ChatMessage>> messageListeners = new ArrayList<>();
    private final List<Consumer<List<OnlineUser>>> userOnlineListeners = new ArrayList<>();
    private final List<Consumer<String>> userOfflineListeners = new ArrayList<>();
    private final List<Runnable> connectedListeners = new ArrayList<>();
    private final List<Consumer<String>> errorListeners = new ArrayList<>();
    private final List<Runnable> closeListeners = new ArrayList<>();
    private boolean isConnected = false;
    private Thread keepAliveThread;
    
    public FishChatService(String token) {
        this.token = token;
    }
    
    /**
     * 连接到聊天服务器
     */
    public void connect() {
        if (isConnected) {
            return;
        }
        
        try {
            // 先获取用户信息
            fetchUserInfo();
            
            // 创建WebSocket连接
            HttpClient client = HttpClient.newHttpClient();
            CompletableFuture<WebSocket> webSocketFuture = client.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(20))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36")
                    .header("Origin", "https://yucoder.cn")
                    .header("Referer", "https://yucoder.cn/")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6")
                    .header("fish-dog-token", token)
                    .buildAsync(URI.create(WS_URL + "?token=" + token), new WebSocketListener());
            
            webSocket = webSocketFuture.join();
            
            // 发送激活消息
            JsonObject activateMsg = new JsonObject();
            activateMsg.addProperty("type", 1);
            webSocket.sendText(gson.toJson(activateMsg), true);
            
            // 开始心跳保活
            startKeepAlive();
            
            // 通知连接成功
            isConnected = true;
            notifyConnected();
            
        } catch (Exception e) {
            String errorMessage = e.getMessage();
            LOG.error("连接聊天服务失败: " + errorMessage, e);
            notifyError(errorMessage);
        }
    }
    
    /**
     * 断开连接
     */
    public void disconnect() {
        if (!isConnected) {
            return;
        }
        
        isConnected = false;
        stopKeepAlive();
        
        if (webSocket != null) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "用户断开连接");
            webSocket = null;
        }
    }
    
    /**
     * 发送消息
     */
    public void sendMessage(String content) {
        if (!isConnected || webSocket == null) {
            notifyError("未连接到服务器");
            return;
        }
        
        try {
            // 生成消息ID
            String messageId = String.valueOf(System.currentTimeMillis());
            
            // 构建消息对象
            JsonObject messageObj = new JsonObject();
            messageObj.addProperty("type", 2);
            messageObj.addProperty("userId", -1);
            
            JsonObject dataObj = new JsonObject();
            dataObj.addProperty("type", "chat");
            
            JsonObject contentObj = new JsonObject();
            
            JsonObject messageContentObj = new JsonObject();
            messageContentObj.addProperty("id", messageId);
            messageContentObj.addProperty("content", content);
            
            JsonObject senderObj = new JsonObject();
            senderObj.addProperty("id", userInfo.getId());
            senderObj.addProperty("name", userInfo.getUserName());
            senderObj.addProperty("avatar", userInfo.getUserAvatar());
            senderObj.addProperty("level", userInfo.getLevel());
            senderObj.addProperty("points", userInfo.getPoints());
            senderObj.addProperty("isAdmin", "admin".equals(userInfo.getUserRole()));
            senderObj.addProperty("region", "未知");
            senderObj.addProperty("country", "未知");
            senderObj.addProperty("avatarFramerUrl", userInfo.getAvatarFramerUrl());
            senderObj.addProperty("titleId", userInfo.getTitleId());
            
            messageContentObj.add("sender", senderObj);
            messageContentObj.addProperty("timestamp", String.valueOf(System.currentTimeMillis()));
            messageContentObj.addProperty("region", "未知");
            messageContentObj.addProperty("country", "未知");
            
            contentObj.add("message", messageContentObj);
            dataObj.add("content", contentObj);
            messageObj.add("data", dataObj);
            
            // 发送消息
            String messageJson = gson.toJson(messageObj);
            webSocket.sendText(messageJson, true);
            
        } catch (Exception e) {
            LOG.error("发送消息失败: " + e.getMessage(), e);
            notifyError("发送消息失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取用户信息
     */
    private void fetchUserInfo() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE_URL + "/api/user/get/login"))
                .header("accept", "*/*")
                .header("fish-dog-token", token)
                .header("User-Agent", "YuCoder-IDEA-Plugin")
                .header("Origin", "https://yucoder.cn")
                .header("Referer", "https://yucoder.cn/")
                .GET()
                .build();
        
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        String responseBody = response.body();
        
        JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);
        if (jsonResponse.has("code") && jsonResponse.get("code").getAsInt() == 0 && jsonResponse.has("data")) {
            userInfo = gson.fromJson(jsonResponse.get("data"), UserInfo.class);
        } else {
            throw new Exception("TOKEN_INVALID");
        }
    }
    
    /**
     * 处理消息
     */
    private void handleMessage(String message) {
        try {
            JsonObject jsonMessage = gson.fromJson(message, JsonObject.class);
            
            // 获取type，可能是数字或字符串
            JsonElement typeElement = jsonMessage.get("type");
            String typeString = null;
            int typeInt = -1;
            
            // 处理不同类型的type字段
            if (typeElement.isJsonPrimitive()) {
                JsonPrimitive typePrimitive = typeElement.getAsJsonPrimitive();
                if (typePrimitive.isNumber()) {
                    typeInt = typePrimitive.getAsInt();
                } else if (typePrimitive.isString()) {
                    typeString = typePrimitive.getAsString();
                }
            }
            
            // 根据数字类型或字符串类型处理消息
            if (typeInt >= 0) {
                // 处理数字类型的消息
                switch (typeInt) {
                    case 1: // 连接确认
                        LOG.info("收到连接确认消息");
                        break;
                    case 2: // 聊天消息
                        if (jsonMessage.has("data")) {
                            JsonObject data = jsonMessage.getAsJsonObject("data");
                            if (data.has("type") && "chat".equals(data.get("type").getAsString())) {
                                ChatMessage chatMessage = parseChatMessage(data);
                                if (chatMessage != null) {
                                    notifyMessageReceived(chatMessage);
                                }
                            }
                        }
                        break;
                    case 3: // 用户上线
                        if (jsonMessage.has("data")) {
                            JsonObject data = jsonMessage.getAsJsonObject("data");
                            if (data.has("users")) {
                                List<OnlineUser> users = parseOnlineUsers(data.getAsJsonArray("users"));
                                notifyUserOnline(users);
                            }
                        }
                        break;
                    case 4: // 用户下线
                        if (jsonMessage.has("data")) {
                            JsonObject data = jsonMessage.getAsJsonObject("data");
                            if (data.has("userId")) {
                                String userId = data.get("userId").getAsString();
                                notifyUserOffline(userId);
                            }
                        }
                        break;
                    default:
                        LOG.info("收到未知类型消息: type=" + typeInt);
                        break;
                }
            } else if (typeString != null) {
                // 处理字符串类型的消息
                switch (typeString) {
                    case "chat":
                        // 处理聊天消息
                        if (jsonMessage.has("data") && jsonMessage.get("data").isJsonObject()) {
                            JsonObject data = jsonMessage.getAsJsonObject("data");
                            if (data.has("message") && data.get("message").isJsonObject()) {
                                ChatMessage chatMessage = gson.fromJson(data.getAsJsonObject("message"), ChatMessage.class);
                                if (chatMessage != null) {
                                    notifyMessageReceived(chatMessage);
                                }
                            }
                        }
                        break;
                    case "userOnline":
                        // 处理用户上线消息
                        LOG.info("收到用户上线消息");
                        if (jsonMessage.has("data") && jsonMessage.get("data").isJsonArray()) {
                            List<OnlineUser> users = new ArrayList<>();
                            JsonArray usersArray = jsonMessage.getAsJsonArray("data");
                            for (JsonElement userElement : usersArray) {
                                if (userElement.isJsonObject()) {
                                    OnlineUser user = gson.fromJson(userElement, OnlineUser.class);
                                    users.add(user);
                                }
                            }
                            notifyUserOnline(users);
                        }
                        break;
                    case "userOffline":
                        // 处理用户下线消息
                        LOG.info("收到用户下线消息");
                        if (jsonMessage.has("data") && jsonMessage.get("data").isJsonPrimitive()) {
                            String userId = jsonMessage.get("data").getAsString();
                            notifyUserOffline(userId);
                        }
                        break;
                    case "userMessageRevoke":
                        // 处理消息撤回，暂时忽略
                        LOG.info("收到消息撤回通知");
                        break;
                    default:
                        LOG.info("收到未知类型字符串消息: type=" + typeString);
                        break;
                }
            }
        } catch (Exception e) {
            LOG.error("处理消息失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 解析聊天消息
     */
    private ChatMessage parseChatMessage(JsonObject data) {
        try {
            if (data.has("content") && data.getAsJsonObject("content").has("message")) {
                return gson.fromJson(data.getAsJsonObject("content").getAsJsonObject("message"), ChatMessage.class);
            }
        } catch (Exception e) {
            LOG.error("解析聊天消息失败: " + e.getMessage(), e);
        }
        return null;
    }
    
    /**
     * 解析在线用户
     */
    private List<OnlineUser> parseOnlineUsers(com.google.gson.JsonArray jsonArray) {
        List<OnlineUser> users = new ArrayList<>();
        try {
            for (int i = 0; i < jsonArray.size(); i++) {
                OnlineUser user = gson.fromJson(jsonArray.get(i), OnlineUser.class);
                users.add(user);
            }
        } catch (Exception e) {
            LOG.error("解析在线用户失败: " + e.getMessage(), e);
        }
        return users;
    }
    
    /**
     * 开始心跳保活
     */
    private void startKeepAlive() {
        stopKeepAlive();
        
        keepAliveThread = new Thread(() -> {
            while (isConnected && webSocket != null) {
                try {
                    // 发送心跳包
                    JsonObject heartbeat = new JsonObject();
                    heartbeat.addProperty("type", 4);
                    webSocket.sendText(gson.toJson(heartbeat), true);
                    LOG.info("发送心跳保活");
                    
                    // 每25秒发送一次心跳
                    Thread.sleep(25000);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    LOG.error("发送心跳包失败: " + e.getMessage(), e);
                    break;
                }
            }
        });
        
        keepAliveThread.setDaemon(true);
        keepAliveThread.start();
    }
    
    /**
     * 停止心跳保活
     */
    private void stopKeepAlive() {
        if (keepAliveThread != null) {
            keepAliveThread.interrupt();
            keepAliveThread = null;
        }
    }
    
    /**
     * WebSocket监听器
     */
    private class WebSocketListener implements WebSocket.Listener {
        private final StringBuilder messageBuffer = new StringBuilder();
        
        @Override
        public void onOpen(WebSocket webSocket) {
            LOG.info("WebSocket连接已建立");
            WebSocket.Listener.super.onOpen(webSocket);
        }
        
        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            messageBuffer.append(data);
            if (last) {
                String fullMessage = messageBuffer.toString();
                messageBuffer.setLength(0);
                
                executorService.submit(() -> handleMessage(fullMessage));
            }
            return WebSocket.Listener.super.onText(webSocket, data, last);
        }
        
        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            return WebSocket.Listener.super.onBinary(webSocket, data, last);
        }
        
        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            LOG.info("WebSocket连接已关闭: " + statusCode + " " + reason);
            isConnected = false;
            notifyClose();
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }
        
        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            LOG.error("WebSocket错误: " + error.getMessage(), error);
            isConnected = false;
            notifyError(error.getMessage());
            WebSocket.Listener.super.onError(webSocket, error);
        }
    }
    
    /**
     * 添加消息监听器
     */
    public void addMessageListener(Consumer<ChatMessage> listener) {
        messageListeners.add(listener);
    }
    
    /**
     * 添加用户上线监听器
     */
    public void addUserOnlineListener(Consumer<List<OnlineUser>> listener) {
        userOnlineListeners.add(listener);
    }
    
    /**
     * 添加用户下线监听器
     */
    public void addUserOfflineListener(Consumer<String> listener) {
        userOfflineListeners.add(listener);
    }
    
    /**
     * 添加连接成功监听器
     */
    public void addConnectedListener(Runnable listener) {
        connectedListeners.add(listener);
    }
    
    /**
     * 添加错误监听器
     */
    public void addErrorListener(Consumer<String> listener) {
        errorListeners.add(listener);
    }
    
    /**
     * 添加WebSocket关闭监听器
     */
    public void addCloseListener(Runnable listener) {
        closeListeners.add(listener);
    }
    
    /**
     * 通知收到消息
     */
    private void notifyMessageReceived(ChatMessage message) {
        if (message != null) {
            for (Consumer<ChatMessage> listener : messageListeners) {
                try {
                    listener.accept(message);
                } catch (Exception e) {
                    LOG.error("通知消息接收失败: " + e.getMessage(), e);
                }
            }
        }
    }
    
    /**
     * 通知用户上线
     */
    private void notifyUserOnline(List<OnlineUser> users) {
        for (Consumer<List<OnlineUser>> listener : userOnlineListeners) {
            try {
                listener.accept(users);
            } catch (Exception e) {
                LOG.error("通知用户上线失败: " + e.getMessage(), e);
            }
        }
    }
    
    /**
     * 通知用户下线
     */
    private void notifyUserOffline(String userId) {
        for (Consumer<String> listener : userOfflineListeners) {
            try {
                listener.accept(userId);
            } catch (Exception e) {
                LOG.error("通知用户下线失败: " + e.getMessage(), e);
            }
        }
    }
    
    /**
     * 通知连接成功
     */
    private void notifyConnected() {
        for (Runnable listener : connectedListeners) {
            try {
                listener.run();
            } catch (Exception e) {
                LOG.error("通知连接成功失败: " + e.getMessage(), e);
            }
        }
    }
    
    /**
     * 通知WebSocket关闭
     */
    private void notifyClose() {
        for (Runnable listener : closeListeners) {
            try {
                listener.run();
            } catch (Exception e) {
                LOG.error("通知WebSocket关闭失败: " + e.getMessage(), e);
            }
        }
    }
    
    /**
     * 通知错误
     */
    private void notifyError(String error) {
        for (Consumer<String> listener : errorListeners) {
            try {
                listener.accept(error);
            } catch (Exception e) {
                LOG.error("通知错误失败: " + e.getMessage(), e);
            }
        }
    }
    
    /**
     * 获取用户信息
     */
    public UserInfo getUserInfo() {
        return userInfo;
    }
    
    /**
     * 是否已连接
     */
    public boolean isConnected() {
        return isConnected;
    }
    
    /**
     * 获取历史消息
     * @param pageSize 消息条数
     * @return 历史消息列表
     */
    public List<ChatMessage> getHistoryMessages(int pageSize) throws Exception {
        if (pageSize <= 0) {
            pageSize = 50;
        }
        
        LOG.info("开始获取历史消息，pageSize=" + pageSize);
        List<ChatMessage> messages = new ArrayList<>();
        HttpClient client = HttpClient.newHttpClient();
        
        // 构建请求体
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("current", 1);  // 从第1页开始
        requestBody.addProperty("pageSize", pageSize);
        requestBody.addProperty("roomId", -1);  // 默认房间
        requestBody.addProperty("sortField", "createTime");
        requestBody.addProperty("sortOrder", "desc");  // 按时间降序排序，最新的在前面
        
        String requestBodyStr = gson.toJson(requestBody);
        LOG.info("请求体: " + requestBodyStr);
        
        // 发送请求
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE_URL + "/api/chat/message/page/vo"))
                .header("accept", "*/*")
                .header("accept-language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("content-type", "application/json")
                .header("fish-dog-token", token)
                .header("Origin", "https://yucoder.cn")
                .header("Referer", "https://yucoder.cn/")
                .header("User-Agent", "YuCoder-IDEA-Plugin")
                .POST(HttpRequest.BodyPublishers.ofString(requestBodyStr))
                .build();
                
        LOG.info("发送历史消息请求...");
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        String responseBody = response.body();
        LOG.info("收到历史消息响应: " + (responseBody.length() > 100 ? responseBody.substring(0, 100) + "..." : responseBody));
        
        JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);
        if (jsonResponse.has("code") && jsonResponse.get("code").getAsInt() == 0 && jsonResponse.has("data")) {
            LOG.info("响应码正确，开始解析数据");
            JsonObject data = jsonResponse.getAsJsonObject("data");
            if (data.has("records") && data.get("records").isJsonArray()) {
                JsonArray records = data.getAsJsonArray("records");
                LOG.info("获取到 " + records.size() + " 条历史消息");
                
                for (JsonElement element : records) {
                    try {
                        JsonObject messageObj = element.getAsJsonObject();
                        LOG.info("处理消息对象: " + messageObj);
                        
                        // 检查是否有messageWrapper字段
                        if (messageObj.has("messageWrapper") && !messageObj.get("messageWrapper").isJsonNull()) {
                            JsonObject messageWrapper = messageObj.getAsJsonObject("messageWrapper");
                            if (messageWrapper.has("message") && !messageWrapper.get("message").isJsonNull()) {
                                // 使用messageWrapper中的message
                                JsonObject actualMessage = messageWrapper.getAsJsonObject("message");
                                
                                // 创建ChatMessage对象
                                ChatMessage chatMessage = new ChatMessage();
                                chatMessage.setId(actualMessage.has("id") ? actualMessage.get("id").getAsString() : messageObj.get("id").getAsString());
                                chatMessage.setContent(actualMessage.get("content").getAsString());
                                chatMessage.setTimestamp(actualMessage.has("timestamp") ? actualMessage.get("timestamp").getAsString() : System.currentTimeMillis() + "");
                                
                                // 解析sender信息
                                if (actualMessage.has("sender") && !actualMessage.get("sender").isJsonNull()) {
                                    JsonObject senderObj = actualMessage.getAsJsonObject("sender");
                                    Sender sender = new Sender();
                                    
                                    // 设置基本信息
                                    sender.setId(senderObj.has("id") ? senderObj.get("id").getAsString() : "unknown");
                                    sender.setName(senderObj.has("name") ? senderObj.get("name").getAsString() : "未知用户");
                                    sender.setAvatar(senderObj.has("avatar") ? senderObj.get("avatar").getAsString() : "");
                                    
                                    // 设置等级和积分
                                    if (senderObj.has("level")) {
                                        sender.setLevel(senderObj.get("level").getAsInt());
                                    }
                                    if (senderObj.has("points")) {
                                        sender.setPoints(senderObj.get("points").getAsInt());
                                    }
                                    
                                    // 设置管理员状态
                                    if (senderObj.has("isAdmin")) {
                                        sender.setAdmin(senderObj.get("isAdmin").getAsBoolean());
                                    }
                                    
                                    // 设置地区信息
                                    if (senderObj.has("region")) {
                                        sender.setRegion(senderObj.get("region").getAsString());
                                    } else {
                                        sender.setRegion("未知");
                                    }
                                    
                                    if (senderObj.has("country")) {
                                        sender.setCountry(senderObj.get("country").getAsString());
                                    } else {
                                        sender.setCountry("未知");
                                    }
                                    
                                    // 设置头像框和称号
                                    if (senderObj.has("avatarFramerUrl")) {
                                        sender.setAvatarFramerUrl(senderObj.get("avatarFramerUrl").getAsString());
                                    } else if (senderObj.has("avatarFrameUrl")) {
                                        sender.setAvatarFramerUrl(senderObj.get("avatarFrameUrl").getAsString());
                                    }
                                    
                                    if (senderObj.has("titleId")) {
                                        sender.setTitleId(senderObj.get("titleId").getAsString());
                                    }
                                    
                                    chatMessage.setSender(sender);
                                    messages.add(chatMessage);
                                    LOG.info("成功解析messageWrapper消息: " + chatMessage.getId() + " - " + chatMessage.getSender().getName() + " - " + 
                                            (chatMessage.getContent().length() > 20 ? chatMessage.getContent().substring(0, 20) + "..." : chatMessage.getContent()));
                                }
                            }
                        } else {
                            // 尝试直接解析旧格式的消息
                            // 创建ChatMessage对象
                            ChatMessage chatMessage = new ChatMessage();
                            chatMessage.setId(messageObj.get("id").getAsString());
                            
                            if (messageObj.has("content")) {
                                chatMessage.setContent(messageObj.get("content").getAsString());
                            } else {
                                LOG.warn("消息没有content字段，跳过");
                                continue;
                            }
                            
                            if (messageObj.has("createTime")) {
                                chatMessage.setTimestamp(messageObj.get("createTime").getAsString());
                            } else {
                                chatMessage.setTimestamp(System.currentTimeMillis() + "");
                            }
                            
                            // 创建发送者信息
                            Sender sender = new Sender();
                            
                            // 解析用户信息
                            JsonObject userObj = null;
                            if (messageObj.has("user") && !messageObj.get("user").isJsonNull()) {
                                userObj = messageObj.getAsJsonObject("user");
                            }
                            
                            if (userObj != null) {
                                sender.setId(userObj.get("id").getAsString());
                                sender.setName(userObj.get("userName").getAsString());
                                sender.setAvatar(userObj.get("userAvatar").getAsString());
                                
                                // 设置用户等级和积分
                                if (userObj.has("level")) {
                                    sender.setLevel(userObj.get("level").getAsInt());
                                }
                                if (userObj.has("points")) {
                                    sender.setPoints(userObj.get("points").getAsInt());
                                }
                                
                                // 设置用户角色
                                if (userObj.has("userRole")) {
                                    sender.setAdmin("admin".equals(userObj.get("userRole").getAsString()));
                                }
                                
                                // 设置用户地区
                                sender.setRegion(messageObj.has("region") ? messageObj.get("region").getAsString() : "未知");
                                sender.setCountry(messageObj.has("country") ? messageObj.get("country").getAsString() : "未知");
                                
                                // 设置头像框和称号
                                if (userObj.has("avatarFramerUrl")) {
                                    sender.setAvatarFramerUrl(userObj.get("avatarFramerUrl").getAsString());
                                }
                                if (userObj.has("titleId")) {
                                    sender.setTitleId(userObj.get("titleId").getAsString());
                                }
                                
                                chatMessage.setSender(sender);
                                messages.add(chatMessage);
                                LOG.info("成功解析旧格式消息: " + chatMessage.getId() + " - " + chatMessage.getSender().getName());
                            } else {
                                LOG.warn("消息没有用户信息，跳过");
                            }
                        }
                    } catch (Exception e) {
                        LOG.warn("解析历史消息失败: " + e.getMessage(), e);
                        e.printStackTrace();
                    }
                }
            } else {
                LOG.warn("响应中没有records数组或格式不正确");
            }
        } else {
            LOG.warn("响应码不正确或没有data字段: " + responseBody);
            throw new Exception("获取历史消息失败: " + (jsonResponse.has("message") ? jsonResponse.get("message").getAsString() : "未知错误"));
        }
        
        LOG.info("历史消息获取完成，共 " + messages.size() + " 条消息");
        return messages;
    }
    
    /**
     * 聊天消息类
     */
    public static class ChatMessage {
        private String id;
        private String content;
        private Sender sender;
        private String timestamp;
        
        public String getId() {
            return id;
        }
        
        public String getContent() {
            return content;
        }
        
        public Sender getSender() {
            return sender;
        }
        
        public String getTimestamp() {
            return timestamp;
        }
        
        public void setId(String id) {
            this.id = id;
        }
        
        public void setContent(String content) {
            this.content = content;
        }
        
        public void setSender(Sender sender) {
            this.sender = sender;
        }
        
        public void setTimestamp(String timestamp) {
            this.timestamp = timestamp;
        }
    }
    
    /**
     * 发送者信息
     */
    public static class Sender {
        private String id;
        private String name;
        private String avatar;
        private int level;
        private int points;
        private boolean isAdmin;
        private String region;
        private String country;
        private String avatarFramerUrl;
        private String titleId;
        
        public String getId() {
            return id;
        }
        
        public String getName() {
            return name;
        }
        
        public String getAvatar() {
            return avatar;
        }
        
        public int getLevel() {
            return level;
        }
        
        public int getPoints() {
            return points;
        }
        
        public boolean isAdmin() {
            return isAdmin;
        }
        
        public String getRegion() {
            return region;
        }
        
        public String getCountry() {
            return country;
        }
        
        public String getAvatarFramerUrl() {
            return avatarFramerUrl;
        }
        
        public String getTitleId() {
            return titleId;
        }
        
        public void setId(String id) {
            this.id = id;
        }
        
        public void setName(String name) {
            this.name = name;
        }
        
        public void setAvatar(String avatar) {
            this.avatar = avatar;
        }
        
        public void setLevel(int level) {
            this.level = level;
        }
        
        public void setPoints(int points) {
            this.points = points;
        }
        
        public void setAdmin(boolean admin) {
            isAdmin = admin;
        }
        
        public void setRegion(String region) {
            this.region = region;
        }
        
        public void setCountry(String country) {
            this.country = country;
        }
        
        public void setAvatarFramerUrl(String avatarFramerUrl) {
            this.avatarFramerUrl = avatarFramerUrl;
        }
        
        public void setTitleId(String titleId) {
            this.titleId = titleId;
        }
    }
    
    /**
     * 在线用户类
     */
    public static class OnlineUser {
        private String id;
        private String name;
        private String avatar;
        private int level;
        private int points;
        private boolean isAdmin;
        private String status;
        
        public String getId() {
            return id;
        }
        
        public String getName() {
            return name;
        }
        
        public String getAvatar() {
            return avatar;
        }
        
        public int getLevel() {
            return level;
        }
        
        public int getPoints() {
            return points;
        }
        
        public boolean isAdmin() {
            return isAdmin;
        }
        
        public String getStatus() {
            return status;
        }
    }
    
    /**
     * 用户信息类
     */
    public static class UserInfo {
        private String id;
        private String userName;
        private String userAvatar;
        private String userProfile;
        private String email;
        private String userRole;
        private int points;
        private int usedPoints;
        private String avatarFramerUrl;
        private String titleId;
        private String titleIdList;
        private int level;
        private String lastSignInDate;
        
        public String getId() {
            return id;
        }
        
        public String getUserName() {
            return userName;
        }
        
        public String getUserAvatar() {
            return userAvatar;
        }
        
        public String getUserProfile() {
            return userProfile;
        }
        
        public String getEmail() {
            return email;
        }
        
        public String getUserRole() {
            return userRole;
        }
        
        public int getPoints() {
            return points;
        }
        
        public int getUsedPoints() {
            return usedPoints;
        }
        
        public String getAvatarFramerUrl() {
            return avatarFramerUrl;
        }
        
        public String getTitleId() {
            return titleId;
        }
        
        public String getTitleIdList() {
            return titleIdList;
        }
        
        public int getLevel() {
            return level;
        }
        
        public String getLastSignInDate() {
            return lastSignInDate;
        }
    }
} 