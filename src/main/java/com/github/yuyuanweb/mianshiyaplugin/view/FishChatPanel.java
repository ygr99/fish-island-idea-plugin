package com.github.yuyuanweb.mianshiyaplugin.view;

import com.github.yuyuanweb.mianshiyaplugin.config.FishChatConfig;
import com.github.yuyuanweb.mianshiyaplugin.service.FishChatService;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.net.URL;
import java.awt.Desktop;
import javax.swing.event.HyperlinkEvent;
import javax.swing.event.HyperlinkListener;
import java.net.URI;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.awt.MediaTracker;

/**
 * 摸鱼室聊天面板
 * 参考VSCode摸鱼插件实现
 */
public class FishChatPanel extends JBPanel<FishChatPanel> implements Disposable {

    // 添加圆形头像类
    private static class CircleAvatarLabel extends JLabel {
        private ImageIcon originalIcon;
        private Shape circle;
        private boolean isCircleReady = false;
        private Timer animationTimer; // 用于GIF动画的定时器
        private long lastRepaintTime = 0; // 记录上次重绘时间
        private boolean isAnimationEnabled = false; // 控制是否启用动画

        public CircleAvatarLabel() {
            setPreferredSize(new Dimension(28, 28));
            setOpaque(false); // 确保背景透明
        }

        public void setOriginalIcon(ImageIcon icon) {
            this.originalIcon = icon;
            isCircleReady = false;
            
            // 禁用GIF动画，避免频繁重绘导致闪烁
            // 只在首次设置图像时重绘一次
            repaint();
            
            // 停止任何可能正在运行的动画计时器
            if (animationTimer != null) {
                animationTimer.stop();
                animationTimer = null;
            }
        }
        
        @Override
        public void removeNotify() {
            super.removeNotify();
            // 组件被移除时停止定时器
            if (animationTimer != null) {
                animationTimer.stop();
                animationTimer = null;
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // 确保圆形区域已创建
            if (!isCircleReady) {
                circle = new Ellipse2D.Double(0, 0, getWidth(), getHeight());
                isCircleReady = true;
            }

            // 创建圆形剪切区域
            g2.setClip(circle);

            if (originalIcon != null) {
                // 绘制图像
                Image img = originalIcon.getImage();
                g2.drawImage(img, 0, 0, getWidth(), getHeight(), this);
            } else {
                // 绘制背景色（用于文字头像）
                g2.setColor(getBackground());
                g2.fill(circle);
                
                // 如果有文本，绘制文本
                if (getText() != null && !getText().isEmpty()) {
                    FontMetrics fm = g2.getFontMetrics();
                    g2.setColor(getForeground());
                    g2.drawString(getText(), 
                            (getWidth() - fm.stringWidth(getText())) / 2, 
                            (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
                }
            }

            // 绘制边框
            g2.setClip(null);
            g2.setColor(UIManager.getColor("Panel.background").darker());
            g2.setStroke(new BasicStroke(1));
            g2.draw(circle);

            g2.dispose();
        }
    }

    private final Project project;
    private final JPanel chatMessagesPanel; // 新增消息面板，使用垂直BoxLayout
    private final JBTextField inputField;
    private final JButton sendButton;
    private final JPanel chatPanel;
    private final List<String> messageHistory = new ArrayList<>();
    private JLabel statusLabel;
    private final JPanel webViewPanel;
    private FishChatService chatService;
    private final JButton connectButton;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");
    private final FishChatConfig config;
    private final JBScrollPane chatScrollPane;

    public FishChatPanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        this.config = new FishChatConfig(project);
        this.setBorder(JBUI.Borders.empty(10));

        // 创建聊天面板
        chatPanel = new JPanel(new BorderLayout());
        chatPanel.setBorder(JBUI.Borders.empty(5));
        chatPanel.setBackground(UIManager.getColor("Panel.background")); // 使用IDE主题颜色

        // 创建消息面板，使用垂直布局
        chatMessagesPanel = new JPanel();
        chatMessagesPanel.setLayout(new BoxLayout(chatMessagesPanel, BoxLayout.Y_AXIS));
        chatMessagesPanel.setBorder(JBUI.Borders.empty(5));
        chatMessagesPanel.setBackground(UIManager.getColor("Panel.background")); // 使用IDE主题颜色
        
        // 添加滚动面板
        chatScrollPane = new JBScrollPane(chatMessagesPanel);
        chatScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        chatScrollPane.setBorder(BorderFactory.createEmptyBorder());
        chatScrollPane.getViewport().setBackground(UIManager.getColor("Panel.background")); // 使用IDE主题颜色
        
        // 添加滚动条监听器，检测用户是否手动滚动
        chatScrollPane.getVerticalScrollBar().addAdjustmentListener(e -> {
            if (!e.getValueIsAdjusting()) {
            JScrollBar scrollBar = chatScrollPane.getVerticalScrollBar();
            // 如果滚动条在最底部或接近底部(差距小于20像素)，则标记应该自动滚动
            int maximum = scrollBar.getMaximum() - scrollBar.getVisibleAmount();
                shouldScrollToBottom = (scrollBar.getValue() >= maximum - 20);
            }
        });
        
        // 创建输入区域
        JPanel inputPanel = new JPanel(new BorderLayout(5, 0));
        inputField = new JBTextField();
        // 使用IDE主题颜色
        inputField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UIManager.getColor("TextField.shadow"), 1, true),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)
        ));
        inputField.setFont(inputField.getFont().deriveFont(13f));
        inputField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    sendMessage();
                }
            }
        });
        
        sendButton = new JButton("发送");
        // 使用IDE主题颜色
        sendButton.setBackground(UIManager.getColor("Button.default.focusedBackground"));
        sendButton.setForeground(UIManager.getColor("Button.default.foreground"));
        sendButton.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        sendButton.setFocusPainted(false);
        sendButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                sendMessage();
            }
        });
        
        inputPanel.add(inputField, BorderLayout.CENTER);
        inputPanel.add(sendButton, BorderLayout.EAST);
        inputPanel.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));
        
        // 创建状态栏
        JPanel statusPanel = new JPanel(new BorderLayout());
        statusLabel = new JLabel("未连接");
        statusLabel.setForeground(new Color(239, 68, 68)); // 红色，参考VSCode插件
        
        // 添加连接按钮
        connectButton = new JButton("连接");
        connectButton.addActionListener(e -> {
            if (chatService == null || !chatService.isConnected()) {
                connectToChat();
            } else {
                disconnectFromChat();
            }
        });
        
        // 移除在线用户计数
        // 添加设置按钮
        JButton settingsButton = new JButton("设置");
        settingsButton.addActionListener(e -> showSettings());
        
        JPanel statusRightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        statusRightPanel.add(settingsButton);
        statusRightPanel.add(connectButton);
        
        statusPanel.add(statusLabel, BorderLayout.WEST);
        statusPanel.add(statusRightPanel, BorderLayout.EAST);
        
        // 添加组件到聊天面板
        chatPanel.add(chatScrollPane, BorderLayout.CENTER);
        chatPanel.add(inputPanel, BorderLayout.SOUTH);
        chatPanel.add(statusPanel, BorderLayout.NORTH);
        
        // 创建主布局
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setDividerLocation(800);
        splitPane.setDividerSize(3);
        
        // 创建摸鱼岛网页视图面板
        webViewPanel = new JPanel(new BorderLayout());
        webViewPanel.add(new JLabel("加载摸鱼岛中..."), BorderLayout.CENTER);
        
        // 添加到分割面板
        splitPane.setLeftComponent(chatPanel);
        splitPane.setRightComponent(webViewPanel);
        
        this.add(splitPane, BorderLayout.CENTER);
        
        // 初始化Web页面
        initWebView();
        
        // 尝试自动连接
        tryAutoConnect();
    }
    
    /**
     * 显示设置对话框
     */
    private void showSettings() {
        // 创建对话框面板
        JPanel dialogPanel = new JPanel(new BorderLayout());
        dialogPanel.setBorder(JBUI.Borders.empty(10));
        
        // 创建设置面板
        JPanel settingsPanel = new JPanel(new GridLayout(3, 2, 10, 10));
        settingsPanel.setBorder(JBUI.Borders.empty(5));
        
        // Token 设置
        JLabel tokenLabel = new JLabel("YuCoder Token:");
        JPasswordField tokenField = new JPasswordField(config.getToken());
        settingsPanel.add(tokenLabel);
        settingsPanel.add(tokenField);
        
        // 显示头像设置
        JLabel avatarLabel = new JLabel("显示用户头像:");
        JCheckBox avatarCheckBox = new JCheckBox();
        avatarCheckBox.setSelected(config.isShowAvatar());
        settingsPanel.add(avatarLabel);
        settingsPanel.add(avatarCheckBox);
        
        // 显示图片设置
        JLabel imagesLabel = new JLabel("显示聊天图片:");
        JCheckBox imagesCheckBox = new JCheckBox();
        imagesCheckBox.setSelected(config.isShowImages());
        settingsPanel.add(imagesLabel);
        settingsPanel.add(imagesCheckBox);
        
        // 添加设置面板到对话框面板
        dialogPanel.add(settingsPanel, BorderLayout.CENTER);
        
        // 显示对话框
        int result = JOptionPane.showConfirmDialog(
                null,
                dialogPanel,
                "摸鱼室设置",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE
        );
        
        // 保存设置
        if (result == JOptionPane.OK_OPTION) {
            String newToken = new String(tokenField.getPassword());
            boolean tokenChanged = !newToken.equals(config.getToken());
            
            config.setToken(newToken);
            config.setShowAvatar(avatarCheckBox.isSelected());
            config.setShowImages(imagesCheckBox.isSelected());
            
            // 如果Token变更，需要重新连接
            if (chatService != null && chatService.isConnected() && tokenChanged) {
                disconnectFromChat();
                connectToChat();
            }
        }
    }
    
    /**
     * 尝试自动连接
     */
    private void tryAutoConnect() {
        SwingUtilities.invokeLater(() -> {
            // 检查是否有保存的Token，有则自动连接
            String token = getYuCoderToken();
            if (token != null && !token.isEmpty()) {
                connectToChat();
            }
        });
    }
    
    /**
     * 获取YuCoder Token
     */
    private String getYuCoderToken() {
        // 从配置中获取Token
        return config.getToken();
    }
    
    /**
     * 连接到聊天室
     */
    private void connectToChat() {
        String token = getYuCoderToken();
        
        if (token == null || token.isEmpty()) {
            token = JOptionPane.showInputDialog(
                    null,
                    "请输入您的YuCoder Token:",
                    "连接到摸鱼室",
                    JOptionPane.QUESTION_MESSAGE
            );
            
            if (token == null || token.isEmpty()) {
                appendSystemMessage("未提供Token，无法连接到摸鱼室。");
                return;
            }
            
            // 保存Token到配置
            config.setToken(token);
        }
        
        setConnectionStatus(false, "正在连接...");
        connectButton.setEnabled(false);
        
        if (chatService != null) {
            chatService.disconnect();
            chatService = null;
        }
        
        // 创建聊天服务
        final String finalToken = token;
        chatService = new FishChatService(finalToken);
        
        // 设置监听器
        setupChatServiceListeners();
        
        // 连接到服务器
        new Thread(() -> {
            try {
                appendSystemMessage("正在连接到摸鱼室...");
                chatService.connect();
                
                // 连接成功后立即加载历史消息
                SwingUtilities.invokeLater(() -> {
                    try {
                        loadHistoryMessages();
                    } catch (Exception e) {
                        appendSystemMessage("加载历史消息失败: " + e.getMessage());
                    }
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    setConnectionStatus(false, "连接失败");
                    connectButton.setText("连接");
                    connectButton.setEnabled(true);
                    appendSystemMessage("连接错误: " + e.getMessage());
                    
                    // 如果是Token无效的错误，清除Token并重新提示输入
                    if (e.getMessage() != null && e.getMessage().contains("TOKEN_INVALID")) {
                        config.setToken("");
                        appendSystemMessage("Token无效，请重新获取有效Token。");
                    }
                });
            }
        }).start();
    }
    
    /**
     * 加载历史消息
     */
    private void loadHistoryMessages() {
        if (chatService == null || !chatService.isConnected()) {
            appendSystemMessage("未连接到服务器，无法加载历史消息");
            return;
        }
        
        try {
            // 显示加载中消息
            appendSystemMessage("正在加载历史消息...");
            
            // 清空当前消息面板
            chatMessagesPanel.removeAll();
            
            // 获取历史消息
            List<FishChatService.ChatMessage> messages = chatService.getHistoryMessages(50);
            
            // 批量添加消息，但暂时不更新UI
            // 使用标志位暂时禁用UI更新
            boolean originalUpdateFlag = pendingUIUpdate;
            pendingUIUpdate = true;
            
            // 显示历史消息
            for (FishChatService.ChatMessage message : messages) {
                if (message != null && message.getSender() != null) {
                    String senderName = message.getSender().getName();
                    String content = message.getContent();
                    String time = formatTime(message.getTimestamp());
                    appendUserMessageCard(senderName, content, time, message.getSender().isAdmin(), message.getSender().getAvatar());
                }
            }
            
            // 恢复原来的更新标志
            pendingUIUpdate = originalUpdateFlag;
            
            // 所有消息添加完成后，一次性更新UI
            chatMessagesPanel.revalidate();
            chatMessagesPanel.repaint();
            
            // 强制启用自动滚动，并滚动到底部
            shouldScrollToBottom = true;
            scrollToBottom();
            
            // 显示加载完成消息
            appendSystemMessage("历史消息加载完成");
        } catch (Exception e) {
            appendSystemMessage("加载历史消息失败: " + e.getMessage());
        }
    }
    
    /**
     * 断开聊天室连接
     */
    private void disconnectFromChat() {
        if (chatService != null) {
            chatService.disconnect();
            chatService = null;
            setConnectionStatus(false, "已断开");
            connectButton.setText("连接");
            appendSystemMessage("已断开与摸鱼室的连接。");
        }
    }
    
    /**
     * 添加消息监听器到聊天服务
     */
    private void setupChatServiceListeners() {
        if (chatService == null) {
            return;
        }
        
        // 添加消息监听器
        chatService.addMessageListener(this::handleChatMessage);
        
        // 移除用户上线计数显示
        chatService.addUserOnlineListener(users -> {
            // 不再显示在线用户信息
        });
        
        // 移除用户下线监听器处理
        chatService.addUserOfflineListener(userId -> {
            // 不处理用户下线通知
        });
        
        // 添加连接成功监听器
        chatService.addConnectedListener(() -> {
            setConnectionStatus(true, "已连接");
            connectButton.setText("断开");
            connectButton.setEnabled(true);
            appendSystemMessage("已连接到摸鱼室");
            
            // 获取用户信息
            FishChatService.UserInfo userInfo = chatService.getUserInfo();
            if (userInfo != null) {
                String welcomeMsg = "Hello，" + userInfo.getUserName();
                appendSystemMessage(welcomeMsg);
            }
        });
        
        // 添加错误监听器
        chatService.addErrorListener(error -> {
            setConnectionStatus(false, "连接失败");
            connectButton.setText("连接");
            connectButton.setEnabled(true);
            appendSystemMessage("连接错误: " + error);
        });
        
        // 添加WebSocket关闭监听器
        chatService.addCloseListener(() -> {
            setConnectionStatus(false, "已断开");
            connectButton.setText("连接");
            connectButton.setEnabled(true);
            appendSystemMessage("与摸鱼室的连接已断开");
        });
    }
    
    /**
     * 解析消息内容，处理[img]标签
     */
    private String parseMessageContent(String content) {
        if (content == null) {
            return "";
        }
        
        // 首先处理所有HTML中可能已经存在的img标签，确保它们有宽度限制
        // 添加width属性来强制限制图片宽度
        String processedContent = content.replaceAll("<img([^>]*)>", 
            "<img$1 width=\"200\" style=\"max-width:200px !important;width:200px !important;height:auto !important;border-radius:4px;display:block;margin:4px 0;\">");
        
        if (config.isShowImages()) {
            // 支持显示图片，替换[img]标签为HTML img标签，参考VSCode插件的样式
            // 同样添加width属性来强制限制图片宽度
            return processedContent.replaceAll("\\[img\\]([^\\[]+)\\[\\/img\\]", 
                "<img src=\"$1\" width=\"200\" alt=\"图片\" style=\"max-width:200px !important;width:200px !important;height:auto !important;border-radius:4px;cursor:pointer;display:block;margin:4px 0;\">");
        } else {
            // 不显示图片，替换为文本标记，添加可点击的效果
            return processedContent.replaceAll("\\[img\\]([^\\[]+)\\[\\/img\\]", 
                "<span style=\"color:#3b82f6;cursor:pointer;text-decoration:underline;\">[图片]</span>");
        }
    }
    
    /**
     * 处理聊天消息
     */
    private void handleChatMessage(FishChatService.ChatMessage message) {
        if (message != null && message.getSender() != null) {
            // 在EDT线程中更新UI
            SwingUtilities.invokeLater(() -> {
                // 添加消息到聊天区域
                String senderName = message.getSender().getName();
                String content = message.getContent();
                String time = formatTime(message.getTimestamp());
                String avatar = message.getSender().getAvatar();
                
                // 收到新消息时强制滚动到底部
                shouldScrollToBottom = true;
                
                // 添加消息卡片 (appendUserMessageCard内部已经调用updateUILater)
                appendUserMessageCard(senderName, content, time, message.getSender().isAdmin(), avatar);
                
                // 强制滚动到底部以查看新消息
                scrollToBottom();
            });
        }
    }
    
    /**
     * 格式化时间戳
     */
    private String formatTime(String timestamp) {
        try {
            long time = Long.parseLong(timestamp);
            return timeFormat.format(new Date(time));
        } catch (Exception e) {
            return timeFormat.format(new Date());
        }
    }
    
    /**
     * 初始化网页视图
     */
    private void initWebView() {
        // 加载摸鱼岛网页
        // 注意: IDEA平台中需要使用JCEF来实现网页浏览功能
        // 这里先留一个简单的实现，后续可以替换为JCEF
        SwingUtilities.invokeLater(() -> {
            JEditorPane editorPane = new JEditorPane();
            editorPane.setEditable(false);
            try {
                editorPane.setPage("https://fish.codebug.icu/");
            } catch (Exception e) {
                editorPane.setText("无法加载摸鱼岛网页，请检查网络连接");
            }
            webViewPanel.removeAll();
            webViewPanel.add(new JBScrollPane(editorPane), BorderLayout.CENTER);
            webViewPanel.revalidate();
            webViewPanel.repaint();
        });
    }
    
    /**
     * 发送消息
     */
    private void sendMessage() {
        String message = inputField.getText().trim();
        if (!message.isEmpty()) {
            if (chatService != null && chatService.isConnected()) {
                // 发送消息到服务器
                try {
                    chatService.sendMessage(message);
                    
                    // 获取用户信息
                    FishChatService.UserInfo userInfo = chatService.getUserInfo();
                    String userName = userInfo != null ? userInfo.getUserName() : "我";
                    boolean isAdmin = userInfo != null && "admin".equals(userInfo.getUserRole());
                    String avatar = userInfo != null ? userInfo.getUserAvatar() : "";
                    
                    // 发送消息后强制启用自动滚动，确保可以看到自己发送的消息
                    shouldScrollToBottom = true;
                    
                    // 本地显示消息
                    appendUserMessageCard(userName, message, timeFormat.format(new Date()), isAdmin, avatar);
                    
                    // 强制滚动到底部
                    scrollToBottom();
                } catch (Exception e) {
                    appendSystemMessage("发送消息失败: " + e.getMessage());
                }
            } else {
                appendSystemMessage("未连接到摸鱼室，无法发送消息。请点击'连接'按钮重新连接。");
                setConnectionStatus(false, "未连接");
                connectButton.setText("连接");
            }
            
            // 清空输入框
            inputField.setText("");
            // 添加到历史记录
            messageHistory.add(message);
        }
    }
    
    /**
     * 添加系统消息
     */
    private void appendSystemMessage(String message) {
        SwingUtilities.invokeLater(() -> {
            JPanel systemMessagePanel = new JPanel(new BorderLayout());
            systemMessagePanel.setBackground(UIManager.getColor("EditorPane.background"));
            
            // 移除边框
            systemMessagePanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
            
            // 处理消息中可能存在的图片，确保它们有宽度限制
            String processedMessage = message.replaceAll("<img([^>]*)>", 
                "<img$1 width=\"200\" style=\"max-width:200px !important;width:200px !important;height:auto !important;\">");
            
            JLabel messageLabel = new JLabel("<html><head><style type=\"text/css\">" +
                    "img { max-width: 200px !important; width: 200px !important; height: auto !important; }" +
                    "</style></head><body style='width: 100%'><span style='color: #888888;'>" + processedMessage + "</span></body></html>");
            
            systemMessagePanel.add(messageLabel, BorderLayout.CENTER);
            
            // 移除最大尺寸限制
            chatMessagesPanel.add(systemMessagePanel);
            chatMessagesPanel.add(Box.createVerticalStrut(5)); // 添加间距
            
            // 不在这里调用revalidate和repaint，而是使用批量更新方法
            updateUILater();
            
            // 立即滚动到底部
            scrollToBottom();
        });
    }
    
    /**
     * 添加用户消息卡片
     */
    private void appendUserMessageCard(String username, String message, String time, boolean isAdmin, String avatar) {
        SwingUtilities.invokeLater(() -> {
            // 创建消息卡片面板
            JPanel messageCard = new JPanel(new BorderLayout(10, 0));
            messageCard.setBackground(UIManager.getColor("EditorPane.background"));
            
            // 移除边框，只保留内边距
            messageCard.setBorder(BorderFactory.createEmptyBorder(3, 3, 5, 3));
            
            // 用户信息区域（头像和名称）
            JPanel userInfoPanel = new JPanel(new BorderLayout(8, 0));
            userInfoPanel.setOpaque(false);
            // 减小固定宽度，改为自适应内容
            userInfoPanel.setPreferredSize(null);
            // 使用水平框布局替代边界布局
            JPanel nameTimePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 4));
            nameTimePanel.setOpaque(false);
            
            // 如果配置允许显示头像
            if (config.isShowAvatar()) {
                // 使用自定义圆形头像组件
                CircleAvatarLabel avatarLabel = new CircleAvatarLabel();
                
                // 创建包含头像的面板，以便更好地对齐
                JPanel avatarPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 4));
                avatarPanel.setOpaque(false);
                avatarPanel.add(avatarLabel);
                
                // 尝试加载头像
                if (avatar != null && !avatar.isEmpty()) {
                    // 先设置一个默认的文字头像作为加载状态
                    avatarLabel.setText("⌛");
                    avatarLabel.setHorizontalAlignment(SwingConstants.CENTER);
                    avatarLabel.setBackground(new Color(120, 120, 120));
                    avatarLabel.setForeground(Color.WHITE);
                    
                    try {
                        // 异步加载头像
                        new Thread(() -> {
                            try {
                                URL url = new URL(avatar);
                                // 使用MediaTracker等待图像完全加载
                                final Image image = Toolkit.getDefaultToolkit().createImage(url);
                                MediaTracker tracker = new MediaTracker(avatarLabel);
                                tracker.addImage(image, 0);
                                try {
                                    tracker.waitForID(0, 3000); // 等待最多3秒
                                } catch (InterruptedException ignored) {}
                                
                                // 创建ImageIcon
                                ImageIcon originalIcon = new ImageIcon(image);
                                
                                SwingUtilities.invokeLater(() -> {
                                    // 设置原始图标到自定义标签
                                    avatarLabel.setText(null); // 清除文字
                                    avatarLabel.setOriginalIcon(originalIcon);
                                    // 仅在头像加载完成后更新UI，避免频繁刷新
                                    updateUILater();
                                });
                            } catch (Exception e) {
                                // 头像加载失败，使用默认文字头像
                                SwingUtilities.invokeLater(() -> {
                                    avatarLabel.setText(username.substring(0, 1).toUpperCase());
                                    avatarLabel.setHorizontalAlignment(SwingConstants.CENTER);
                                    avatarLabel.setBackground(new Color(100, 149, 237));
                                    avatarLabel.setForeground(Color.WHITE);
                                    // 不需要设置setOpaque(true)，CircleAvatarLabel会处理绘制
                                    // 仅在头像设置完成后更新UI
                                    updateUILater();
                                });
                            }
                        }).start();
                    } catch (Exception e) {
                        // 头像加载失败，使用默认文字头像
                        avatarLabel.setText(username.substring(0, 1).toUpperCase());
                        avatarLabel.setHorizontalAlignment(SwingConstants.CENTER);
                        avatarLabel.setBackground(new Color(100, 149, 237));
                        avatarLabel.setForeground(Color.WHITE);
                        // 不需要设置setOpaque(true)，CircleAvatarLabel会处理绘制
                    }
                } else {
                    // 没有头像URL，显示首字母
                    avatarLabel.setText(username.substring(0, 1).toUpperCase());
                    avatarLabel.setHorizontalAlignment(SwingConstants.CENTER);
                    avatarLabel.setBackground(new Color(100, 149, 237));
                    avatarLabel.setForeground(Color.WHITE);
                    // 不需要设置setOpaque(true)，CircleAvatarLabel会处理绘制
                }
                
                userInfoPanel.add(avatarPanel, BorderLayout.WEST);
            }
            
            // 用户名
            String displayName = isAdmin ? "[管理员] " + username : username;
            JLabel nameLabel = new JLabel(displayName);
            nameLabel.setForeground(isAdmin ? new Color(220, 38, 38) : new Color(59, 130, 246));
            nameLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 5));
            nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD));
            nameTimePanel.add(nameLabel);
            
            // 时间标签
            JLabel timeLabel = new JLabel(time);
            timeLabel.setForeground(new Color(150, 150, 150));
            timeLabel.setHorizontalAlignment(SwingConstants.LEFT);
            timeLabel.setFont(timeLabel.getFont().deriveFont(10.0f));
            nameTimePanel.add(timeLabel);
            
            userInfoPanel.add(nameTimePanel, BorderLayout.CENTER);
            
            // 消息内容
            JPanel contentPanel = new JPanel(new BorderLayout());
            contentPanel.setOpaque(false);
            contentPanel.setBorder(BorderFactory.createEmptyBorder(2, 3, 5, 5));
            
            // 处理消息内容，支持图片和链接
            if (message.contains("<img") || message.contains("[img]")) {
                // 使用JEditorPane显示富文本
                JEditorPane contentPane = new JEditorPane();
                contentPane.setContentType("text/html");
                
                // 设置JEditorPane的CSS属性，启用CSS渲染支持
                contentPane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
                
                // 先解析消息内容中的[img]标签
                String parsedMessage = parseMessageContent(message);
                
                // 使用更强的CSS样式确保图片宽度限制生效
                String htmlContent = "<html><head><style type=\"text/css\">" +
                        "body { width: 100%; font-family: var(--vscode-font-family); font-size: 12pt; }" +
                        "img { max-width: 200px !important; width: expression(this.width > 200 ? 200 : true); height: auto !important; display: block; }" +
                        "</style></head><body>" + 
                        parsedMessage.replace("\n", "<br>") + "</body></html>";
                        
                // 对HTML内容进行额外处理，强制设置img的宽度属性
                htmlContent = htmlContent.replaceAll("<img([^>]*?)>", "<img$1 width=\"200\">");
                
                contentPane.setText(htmlContent);
                contentPane.setEditable(false);
                contentPane.setBackground(UIManager.getColor("EditorPane.background")); // 使用IDE主题颜色
                contentPane.setBorder(null);
                contentPane.setOpaque(false); // 使背景透明
                
                // 添加超链接监听器
                contentPane.addHyperlinkListener(e -> {
                    if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                        try {
                            // 如果是图片链接，可以执行特殊处理
                            if (e.getDescription() != null && e.getDescription().startsWith("image:")) {
                                // 处理图片点击事件
                                String imageUrl = e.getDescription().substring(6);
                                // 这里可以添加图片预览或其他操作
                                Desktop.getDesktop().browse(new URI(imageUrl));
                            } else {
                            Desktop.getDesktop().browse(e.getURL().toURI());
                            }
                        } catch (Exception ex) {
                            appendSystemMessage("无法打开链接: " + ex.getMessage());
                        }
                    }
                });
                
                contentPanel.add(contentPane, BorderLayout.CENTER);
            } else {
                // 使用普通JLabel显示文本
                JLabel contentLabel = new JLabel("<html><div style='width: 100%; line-height: 1.4;'>" + 
                        message.replace("\n", "<br>").replace("<", "&lt;").replace(">", "&gt;") + "</div></html>");
                contentLabel.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 0));
                contentLabel.setForeground(UIManager.getColor("Label.foreground")); // 使用IDE主题文本颜色
                contentPanel.add(contentLabel, BorderLayout.CENTER);
            }
            
            // 添加组件到消息卡片
            messageCard.add(userInfoPanel, BorderLayout.NORTH);
            messageCard.add(contentPanel, BorderLayout.CENTER);
            
            // 添加到消息面板
            chatMessagesPanel.add(messageCard);
            chatMessagesPanel.add(Box.createVerticalStrut(8)); // 添加间距

            // 不在这里调用revalidate和repaint，改用批量更新UI的方法
            updateUILater();
                
            // 强制滚动到底部
            scrollToBottom();
        });
    }
    
    // 添加消息计数器，用于批量更新
    private int pendingMessageCount = 0;
    private boolean pendingUIUpdate = false;
    private Timer uiUpdateTimer = null;
    
    /**
     * 延迟批量更新UI，减少闪烁
     */
    private void updateUILater() {
        pendingMessageCount++;
        
        if (!pendingUIUpdate) {
            pendingUIUpdate = true;
            
            if (uiUpdateTimer != null) {
                uiUpdateTimer.stop();
            }
            
            // 使用计时器延迟更新，延长到300毫秒，合并短时间内的多次更新请求
            uiUpdateTimer = new Timer(300, e -> {
                if (pendingUIUpdate) {
                    // 实际执行UI更新
                    chatMessagesPanel.revalidate();
                    
                    // 只有当有多条消息需要更新时才调用repaint
                    // 这样可以减少不必要的重绘
                    if (pendingMessageCount > 0) {
                        chatMessagesPanel.repaint();
                    }
                    
                    pendingUIUpdate = false;
                    pendingMessageCount = 0;
                }
                ((Timer)e.getSource()).stop();
            });
            uiUpdateTimer.setRepeats(false);
            uiUpdateTimer.start();
        }
    }
    
    /**
     * 智能滚动：根据当前滚动位置决定是否需要滚动到底部
     * 如果滚动条已经在底部，则自动滚动到新内容
     * 如果用户已经向上滚动查看历史消息，则不自动滚动
     */
    private boolean shouldScrollToBottom = true;
    private void scrollToBottom() {
        SwingUtilities.invokeLater(() -> {
            if (shouldScrollToBottom) {
                JScrollBar vertical = chatScrollPane.getVerticalScrollBar();
                // 确保真正滚动到最底部
                vertical.setValue(vertical.getMaximum());
            }
        });
    }
    
    /**
     * 设置连接状态
     */
    public void setConnectionStatus(boolean connected, String statusText) {
        statusLabel.setText(statusText);
        if (connected) {
            statusLabel.setForeground(new Color(34, 197, 94)); // 绿色，参考VSCode插件
        } else {
            statusLabel.setForeground(new Color(239, 68, 68)); // 红色，参考VSCode插件
        }
    }

    @Override
    public void dispose() {
        // 断开连接
        if (chatService != null) {
            chatService.disconnect();
            chatService = null;
        }
        
        // 停止UI更新计时器
        if (uiUpdateTimer != null) {
            uiUpdateTimer.stop();
            uiUpdateTimer = null;
        }
        
        // 清理资源
        messageHistory.clear();
    }
} 