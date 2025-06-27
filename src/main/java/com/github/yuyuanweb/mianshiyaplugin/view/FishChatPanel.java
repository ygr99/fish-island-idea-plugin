package com.github.yuyuanweb.mianshiyaplugin.view;

import com.github.yuyuanweb.mianshiyaplugin.config.FishChatConfig;
import com.github.yuyuanweb.mianshiyaplugin.service.FishChatService;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.net.URL;
import java.awt.Desktop;
import javax.swing.event.HyperlinkEvent;
import javax.swing.event.HyperlinkListener;
import java.net.URI;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.awt.MediaTracker;
import java.awt.Cursor;
import javax.imageio.ImageIO;

/**
 * 摸鱼室聊天面板
 * 参考VSCode摸鱼插件实现
 */
public class FishChatPanel extends JBPanel<FishChatPanel> implements Disposable {
    private static final Logger LOG = Logger.getInstance(FishChatPanel.class);
    
    // 静态实例，用于保持状态
    private static FishChatPanel INSTANCE;
    
    // 获取单例实例
    public static synchronized FishChatPanel getInstance(Project project) {
        if (INSTANCE == null) {
            INSTANCE = new FishChatPanel(project);
        }
        return INSTANCE;
    }

    // 添加圆形头像类
    private static class CircleAvatarLabel extends JLabel {
        private ImageIcon originalIcon;
        private Shape circle;
        private boolean isCircleReady = false;
        private Timer animationTimer; // 用于GIF动画的定时器
        private long lastRepaintTime = 0; // 记录上次重绘时间
        private boolean isAnimationEnabled = false; // 控制是否启用动画
        private Image staticImage; // 存储GIF的第一帧或静态图像

        public CircleAvatarLabel() {
            setPreferredSize(new Dimension(24, 24));
            setOpaque(false); // 确保背景透明
        }

        public void setOriginalIcon(ImageIcon icon) {
            this.originalIcon = icon;
            isCircleReady = false;
            
            // 停止任何可能正在运行的动画计时器
            if (animationTimer != null) {
                animationTimer.stop();
                animationTimer = null;
            }
            
            // 处理图像，确保即使是GIF也能显示
            if (icon != null) {
                try {
                    // 使用ImageIO直接从URL加载图像
                    // 这需要在appendUserMessageCard方法中传递URL而不是ImageIcon
                    if (icon.getImage() != null) {
                        // 直接使用原始图像
                        this.staticImage = icon.getImage();
                        
                        // 强制立即加载图像
                        MediaTracker tracker = new MediaTracker(this);
                        tracker.addImage(this.staticImage, 0);
                        try {
                            tracker.waitForID(0, 500); // 等待最多0.5秒
                        } catch (InterruptedException ignored) {}
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    // 如果提取失败，设置为null
                    this.staticImage = null;
                }
            } else {
                this.staticImage = null;
            }
            
            // 重绘组件
            repaint();
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

            if (staticImage != null) {
                // 绘制静态图像
                g2.drawImage(staticImage, 0, 0, getWidth(), getHeight(), this);
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

    private FishChatPanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        this.config = new FishChatConfig(project);
        this.setBorder(JBUI.Borders.empty(0)); // 移除外边距，使面板填满整个工具窗口

        // 创建聊天面板
        chatPanel = new JPanel(new BorderLayout());
        chatPanel.setBorder(JBUI.Borders.empty(0)); // 移除内边距，使内容填满整个面板
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
                // 获取滚动条位置信息
                JScrollBar scrollBar = (JScrollBar) e.getSource();
                int value = scrollBar.getValue();
                int extent = scrollBar.getVisibleAmount();
                int maximum = scrollBar.getMaximum();
                int bottomPosition = maximum - extent;
                
                // 更新shouldScrollToBottom标志，根据滚动条位置判断
                boolean nearBottom = (bottomPosition - value <= 30);
                
                // 只有当状态变化时才记录日志
                if (shouldScrollToBottom != nearBottom) {
                    LOG.info("滚动条位置变化: value=" + value + ", max=" + maximum + 
                            ", extent=" + extent + ", 距底部=" + (bottomPosition - value) + 
                            ", 自动滚动=" + nearBottom);
                }
                
                shouldScrollToBottom = nearBottom;
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
        statusLabel.setForeground(new Color(120, 120, 120)); // 灰色，更加隐蔽
        
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
        
        // 直接添加聊天面板到主面板，不使用分割面板
        this.add(chatPanel, BorderLayout.CENTER);
        
        // 创建摸鱼岛网页视图面板（但不显示它）
        webViewPanel = new JPanel(new BorderLayout());
        webViewPanel.add(new JLabel("加载摸鱼岛中..."), BorderLayout.CENTER);
        
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
        JCheckBox avatarCheckBox = new JCheckBox("", config.isShowAvatar());
        // 记录当前设置状态
        LOG.info("设置对话框 - 当前头像显示设置: " + config.isShowAvatar());
        settingsPanel.add(avatarLabel);
        settingsPanel.add(avatarCheckBox);
        
        // 显示图片设置
        JLabel imagesLabel = new JLabel("显示聊天图片:");
        JCheckBox imagesCheckBox = new JCheckBox("", config.isShowImages());
        // 记录当前设置状态
        LOG.info("设置对话框 - 当前图片显示设置: " + config.isShowImages());
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
            
            // 检查头像设置是否变更
            boolean avatarChanged = config.isShowAvatar() != avatarCheckBox.isSelected();
            boolean imagesChanged = config.isShowImages() != imagesCheckBox.isSelected();
            
            config.setShowAvatar(avatarCheckBox.isSelected());
            config.setShowImages(imagesCheckBox.isSelected());
            
            // 如果Token变更，需要重新连接
            if (chatService != null && chatService.isConnected() && tokenChanged) {
                disconnectFromChat();
                connectToChat();
            } 
            // 如果头像或图片设置变更，重新加载消息
            else if (avatarChanged || imagesChanged) {
                // 记录当前设置变更
                LOG.info("设置已更改: 显示头像=" + avatarCheckBox.isSelected() + 
                        ", 显示图片=" + imagesCheckBox.isSelected());
                
                // 如果连接状态正常，重新加载消息
                if (chatService != null && chatService.isConnected()) {
                    loadHistoryMessages();
                }
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
            chatMessagesPanel.revalidate();
            chatMessagesPanel.repaint();
            
            // 获取历史消息
            List<FishChatService.ChatMessage> messages = chatService.getHistoryMessages(50);
            
            if (messages.isEmpty()) {
                appendSystemMessage("没有历史消息");
                return;
            }
            
            // 反转消息顺序，确保按时间顺序显示
            List<FishChatService.ChatMessage> sortedMessages = new ArrayList<>(messages);
            java.util.Collections.reverse(sortedMessages);
            
            appendSystemMessage("获取到 " + sortedMessages.size() + " 条历史消息，正在显示...");
            
            // 使用计数器来跟踪消息显示进度
            final int[] counter = {0};
            final int totalMessages = sortedMessages.size();
            
            // 使用Timer分批添加消息，避免UI卡顿
            Timer messageTimer = new Timer(10, null);
            messageTimer.addActionListener(e -> {
                if (counter[0] < totalMessages) {
                    FishChatService.ChatMessage message = sortedMessages.get(counter[0]);
                    if (message != null && message.getSender() != null) {
                        String senderName = message.getSender().getName();
                        String content = message.getContent();
                        String time = formatTime(message.getTimestamp());
                        
                        // 直接添加消息卡片到面板，不使用appendUserMessageCard方法
                        // 这样可以避免异步处理导致的问题
                        JPanel messageCard = createMessageCard(senderName, content, time, message.getSender().isAdmin(), message.getSender().getAvatar());
                        chatMessagesPanel.add(messageCard);
                        chatMessagesPanel.add(Box.createVerticalStrut(8)); // 添加间距
                    }
                    
                    counter[0]++;
                    
                    // 每添加10条消息更新一次UI
                    if (counter[0] % 10 == 0 || counter[0] == totalMessages) {
                        chatMessagesPanel.revalidate();
                        chatMessagesPanel.repaint();
                    }
                    
                    // 所有消息添加完成
                    if (counter[0] == totalMessages) {
                        messageTimer.stop();
                        
                        // 加载完历史消息后总是滚动到底部
                        LOG.info("历史消息加载完成，滚动到底部");
                        
                        // 使用Timer延迟执行，确保在UI更新后滚动
                        Timer scrollTimer = new Timer(100, evt -> {
                            JScrollBar vertical = chatScrollPane.getVerticalScrollBar();
                            vertical.setValue(vertical.getMaximum());
                            
                            // 再次延迟执行，确保滚动到底部
                            SwingUtilities.invokeLater(() -> {
                                vertical.setValue(vertical.getMaximum());
                                
                                LOG.info("完成历史消息滚动: value=" + vertical.getValue() + 
                                        ", max=" + vertical.getMaximum());
                                
                                // 显示加载完成消息
                                appendSystemMessage("历史消息加载完成");
                            });
                            
                            ((Timer)evt.getSource()).stop();
                        });
                        scrollTimer.setRepeats(false);
                        scrollTimer.start();
                    }
                } else {
                    messageTimer.stop();
                }
            });
            
            // 启动定时器
            messageTimer.start();
            
        } catch (Exception e) {
            e.printStackTrace();
            appendSystemMessage("加载历史消息失败: " + e.getMessage());
        }
    }
    
    /**
     * 创建消息卡片（同步版本，不使用SwingUtilities.invokeLater）
     */
    private JPanel createMessageCard(String username, String message, String time, boolean isAdmin, String avatar) {
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
        
                    // 头像区域 - 无论是否显示头像，都保留相同的布局结构
            JPanel avatarPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 4));
            avatarPanel.setOpaque(false);
            
                    // 检查并记录头像显示设置
        boolean showAvatar = config.isShowAvatar();
        LOG.info("创建消息卡片: 用户=" + username + ", 显示头像设置=" + showAvatar);
        
        // 如果配置允许显示头像
        if (showAvatar) {
            // 使用简单的文字头像，不加载图片
            JLabel avatarLabel = new JLabel(username.substring(0, 1).toUpperCase());
                avatarLabel.setPreferredSize(new Dimension(24, 24));
                avatarLabel.setHorizontalAlignment(SwingConstants.CENTER);
                avatarLabel.setBackground(new Color(100, 149, 237));
                avatarLabel.setForeground(Color.WHITE);
                avatarLabel.setOpaque(true);
                
                avatarPanel.add(avatarLabel);
            } else {
                // 如果不显示头像，添加一个空白占位符以保持布局一致
                JLabel spacerLabel = new JLabel(" ");
                spacerLabel.setPreferredSize(new Dimension(5, 24));
                avatarPanel.add(spacerLabel);
            }
            
            userInfoPanel.add(avatarPanel, BorderLayout.WEST);
        
                    // 用户名 - 不显示管理员标记，使用更隐蔽的颜色
            String displayName = username;  // 不再添加[管理员]前缀
            JLabel nameLabel = new JLabel(displayName);
            // 使用更隐蔽的颜色，避免红色等醒目颜色
            nameLabel.setForeground(isAdmin ? new Color(70, 110, 126) : new Color(90, 120, 140));
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
        
        // 提取消息中的图片URL
        List<String> imageUrls = new ArrayList<>();
        if (message.contains("[img]")) {
            Pattern pattern = Pattern.compile("\\[img\\]([^\\[]+)\\[\\/img\\]");
            java.util.regex.Matcher matcher = pattern.matcher(message);
            while (matcher.find()) {
                imageUrls.add(matcher.group(1));
            }
        }
        
        // 创建内容面板
        JPanel messageContentPanel = new JPanel();
        messageContentPanel.setLayout(new BoxLayout(messageContentPanel, BoxLayout.Y_AXIS));
        messageContentPanel.setOpaque(false);
        
        // 处理纯文本部分（替换掉图片标签）
        String textContent = message.replaceAll("\\[img\\][^\\[]+\\[\\/img\\]", "").trim();
        
        // 如果有文本内容，添加文本标签
        if (!textContent.isEmpty()) {
            JLabel textLabel = new JLabel("<html><div style='width: 100%; line-height: 1.4;'>" + 
                    textContent.replace("\n", "<br>").replace("<", "&lt;").replace(">", "&gt;") + "</div></html>");
            textLabel.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 5));
            textLabel.setForeground(UIManager.getColor("Label.foreground"));
            textLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            messageContentPanel.add(textLabel);
        }
        
        // 处理图片
        if (!imageUrls.isEmpty()) {
            for (String imageUrl : imageUrls) {
                // 创建图片面板
                JPanel imagePanel = new JPanel();
                imagePanel.setLayout(new BoxLayout(imagePanel, BoxLayout.Y_AXIS));
                imagePanel.setOpaque(false);
                imagePanel.setAlignmentX(Component.LEFT_ALIGNMENT);
                imagePanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
                
                // 创建图片链接按钮
                JButton imageLink = new JButton("[图片]");
                imageLink.setForeground(new Color(59, 130, 246));
                imageLink.setBorderPainted(false);
                imageLink.setContentAreaFilled(false);
                imageLink.setFocusPainted(false);
                imageLink.setCursor(new Cursor(Cursor.HAND_CURSOR));
                imageLink.setAlignmentX(Component.LEFT_ALIGNMENT);
                
                // 创建图片标签（初始隐藏）
                JLabel imageLabel = new JLabel();
                imageLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
                imageLabel.setVisible(config.isShowImages()); // 根据设置决定初始状态
                
                // 异步加载图片
                new Thread(() -> {
                    try {
                        URL url = new URL(imageUrl);
                        BufferedImage originalImage = ImageIO.read(url);
                        
                        if (originalImage != null) {
                            // 调整图片大小，最大宽度200像素
                            int originalWidth = originalImage.getWidth();
                            int originalHeight = originalImage.getHeight();
                            int newWidth = Math.min(originalWidth, 200);
                            int newHeight = (int) ((double) originalHeight / originalWidth * newWidth);
                            
                            // 创建缩放后的图片
                            Image scaledImage = originalImage.getScaledInstance(newWidth, newHeight, Image.SCALE_SMOOTH);
                            ImageIcon icon = new ImageIcon(scaledImage);
                            
                            SwingUtilities.invokeLater(() -> {
                                imageLabel.setIcon(icon);
                                imagePanel.revalidate();
                                imagePanel.repaint();
                            });
                        }
                    } catch (Exception e) {
                        LOG.warn("无法加载图片: " + e.getMessage());
                        SwingUtilities.invokeLater(() -> {
                            imageLabel.setText("无法加载图片");
                        });
                    }
                }).start();
                
                // 添加点击事件，切换图片显示/隐藏
                imageLink.addActionListener(e -> {
                    boolean visible = !imageLabel.isVisible();
                    imageLabel.setVisible(visible);
                    imagePanel.revalidate();
                    imagePanel.repaint();
                });
                
                // 添加到图片面板
                imagePanel.add(imageLink);
                imagePanel.add(imageLabel);
                
                // 添加到消息内容面板
                messageContentPanel.add(imagePanel);
            }
        }
        
        contentPanel.add(messageContentPanel, BorderLayout.CENTER);
        
        // 添加组件到消息卡片
        messageCard.add(userInfoPanel, BorderLayout.NORTH);
        messageCard.add(contentPanel, BorderLayout.CENTER);
        
        return messageCard;
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
     * 处理HTML内容，主要用于系统消息
     */
    private String processHtmlContent(String content) {
        if (content == null) {
            return "";
        }
        
        // 处理HTML中的img标签，确保它们有宽度限制
        return content.replaceAll("<img([^>]*)>", 
            "<img$1 width=\"200\" style=\"max-width:200px !important;width:200px !important;height:auto !important;border-radius:4px;display:block;margin:4px 0;\">");
    }
    
    /**
     * 处理聊天消息
     */
    private void handleChatMessage(FishChatService.ChatMessage message) {
        if (message != null && message.getSender() != null) {
            // 在EDT线程中更新UI
            SwingUtilities.invokeLater(() -> {
                try {
                    // 添加消息到聊天区域
                    String senderName = message.getSender().getName();
                    String content = message.getContent();
                    String time = formatTime(message.getTimestamp());
                    String avatar = message.getSender().getAvatar();
                    
                    // 保存当前滚动条位置信息
                    JScrollBar vertical = chatScrollPane.getVerticalScrollBar();
                    int currentValue = vertical.getValue();
                    int maximum = vertical.getMaximum();
                    int extent = vertical.getVisibleAmount();
                    int bottomPosition = maximum - extent;
                    
                    // 判断是否在底部或接近底部（30像素以内）
                    boolean wasAtBottom = (bottomPosition - currentValue <= 30);
                    
                    LOG.info("收到新消息前滚动位置: value=" + currentValue + 
                            ", max=" + maximum + ", extent=" + extent + 
                            ", 距底部=" + (bottomPosition - currentValue) + 
                            ", 在底部=" + wasAtBottom);
                    
                    // 添加消息卡片
                    JPanel messageCard = createMessageCard(senderName, content, time, message.getSender().isAdmin(), avatar);
                    chatMessagesPanel.add(messageCard);
                    chatMessagesPanel.add(Box.createVerticalStrut(8)); // 添加间距
                    
                    // 立即更新UI
                    chatMessagesPanel.revalidate();
                    chatMessagesPanel.repaint();
                    
                    // 如果之前在底部或接近底部，则滚动到底部
                    if (wasAtBottom) {
                        LOG.info("新消息自动滚动到底部");
                        
                        // 使用Timer延迟执行，确保在UI更新后滚动
                        Timer timer = new Timer(50, e -> {
                            vertical.setValue(vertical.getMaximum());
                            
                            // 再次延迟执行，确保滚动到底部
                            SwingUtilities.invokeLater(() -> {
                                vertical.setValue(vertical.getMaximum());
                                
                                // 第三次尝试，确保滚动到底部
                                Timer finalTimer = new Timer(50, event -> {
                                    vertical.setValue(vertical.getMaximum());
                                    ((Timer)event.getSource()).stop();
                                    
                                    LOG.info("完成新消息滚动: value=" + vertical.getValue() + 
                                            ", max=" + vertical.getMaximum());
                                });
                                finalTimer.setRepeats(false);
                                finalTimer.start();
                            });
                            
                            ((Timer)e.getSource()).stop();
                        });
                        timer.setRepeats(false);
                        timer.start();
                    } else {
                        LOG.info("用户正在查看历史消息，不自动滚动");
                    }
                    
                } catch (Exception e) {
                    LOG.error("处理聊天消息失败: " + e.getMessage(), e);
                }
            });
        }
    }
    
    /**
     * 格式化时间戳
     */
    private String formatTime(String timestamp) {
        try {
            // 尝试直接解析时间戳
            long time = 0;
            if (timestamp.contains("T") && timestamp.contains("Z")) {
                // ISO 8601格式，如: 2025-06-27T03:33:00.750Z
                try {
                    java.time.Instant instant = java.time.Instant.parse(timestamp);
                    time = instant.toEpochMilli();
                } catch (Exception e) {
                    // 解析失败，尝试其他方式
                }
            } else {
                // 尝试直接解析为长整型
                time = Long.parseLong(timestamp);
            }
            
            return timeFormat.format(new Date(time));
        } catch (Exception e) {
            // 所有解析方式都失败，返回当前时间
            return timeFormat.format(new Date());
        }
    }
    
    /**
     * 初始化网页视图
     */
    private void initWebView() {
        // 由于我们不再显示网页视图面板，这个方法可以留空
        // 或者可以在这里添加一个按钮，点击后在浏览器中打开摸鱼岛网页
        LOG.info("网页视图已禁用");
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
                    
                    // 本地显示消息 - 直接创建并添加消息卡片，而不是使用appendUserMessageCard
                    JPanel messageCard = createMessageCard(userName, message, timeFormat.format(new Date()), isAdmin, avatar);
                    chatMessagesPanel.add(messageCard);
                    chatMessagesPanel.add(Box.createVerticalStrut(8)); // 添加间距
                    
                    // 立即更新UI
                    chatMessagesPanel.revalidate();
                    chatMessagesPanel.repaint();
                    
                    // 发送消息后总是滚动到底部，确保可以看到自己发送的消息
                    LOG.info("发送消息后滚动到底部");
                    
                    // 使用Timer延迟执行，确保在UI更新后滚动
                    Timer timer = new Timer(50, e -> {
                        JScrollBar vertical = chatScrollPane.getVerticalScrollBar();
                        vertical.setValue(vertical.getMaximum());
                        
                        // 再次延迟执行，确保滚动到底部
                        SwingUtilities.invokeLater(() -> {
                            vertical.setValue(vertical.getMaximum());
                            
                            LOG.info("完成发送消息滚动: value=" + vertical.getValue() + 
                                    ", max=" + vertical.getMaximum());
                        });
                        
                        ((Timer)e.getSource()).stop();
                    });
                    timer.setRepeats(false);
                    timer.start();
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
            
            // 不再需要这个处理，已经移到processHtmlContent方法中
            
            String htmlContent = processHtmlContent(message);
            JLabel messageLabel = new JLabel("<html><head><style type=\"text/css\">" +
                    "img { max-width: 200px !important; width: 200px !important; height: auto !important; }" +
                    "</style></head><body style='width: 100%'><span style='color: #888888;'>" + htmlContent + "</span></body></html>");
            
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
            
                    // 头像区域 - 无论是否显示头像，都保留相同的布局结构
        JPanel avatarPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 4));
        avatarPanel.setOpaque(false);
        
        // 如果配置允许显示头像
        if (config.isShowAvatar()) {
            // 使用自定义圆形头像组件
            CircleAvatarLabel avatarLabel = new CircleAvatarLabel();
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
                            // 使用ImageIO直接从URL加载图像
                            // 注意：ImageIO.read()不支持GIF动画，但可以读取第一帧
                            BufferedImage bufferedImage = ImageIO.read(url);
                            
                            if (bufferedImage != null) {
                                // 如果成功读取了图像，创建ImageIcon
                                ImageIcon originalIcon = new ImageIcon(bufferedImage);
                                
                                SwingUtilities.invokeLater(() -> {
                                    // 设置原始图标到自定义标签
                                    avatarLabel.setText(null); // 清除文字
                                    avatarLabel.setOriginalIcon(originalIcon);
                                    // 仅在头像加载完成后更新UI，避免频繁刷新
                                    updateUILater();
                                });
                            } else {
                                // ImageIO无法读取图像，尝试使用Toolkit方法
                                final Image image = Toolkit.getDefaultToolkit().createImage(url);
                                MediaTracker tracker = new MediaTracker(avatarLabel);
                                tracker.addImage(image, 0);
                                try {
                                    tracker.waitForID(0, 2000); // 等待最多2秒
                                } catch (InterruptedException ignored) {}
                                
                                if (!tracker.isErrorAny()) {
                                    // 创建静态图像
                                    BufferedImage staticImg = new BufferedImage(
                                        Math.max(1, image.getWidth(null)), 
                                        Math.max(1, image.getHeight(null)), 
                                        BufferedImage.TYPE_INT_ARGB);
                                    Graphics2D g2d = staticImg.createGraphics();
                                    g2d.drawImage(image, 0, 0, null);
                                    g2d.dispose();
                                    
                                    ImageIcon originalIcon = new ImageIcon(staticImg);
                                    
                                    SwingUtilities.invokeLater(() -> {
                                        // 设置原始图标到自定义标签
                                        avatarLabel.setText(null); // 清除文字
                                        avatarLabel.setOriginalIcon(originalIcon);
                                        // 仅在头像加载完成后更新UI，避免频繁刷新
                                        updateUILater();
                                    });
                                } else {
                                    throw new Exception("无法加载图像");
                                }
                            }
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
        } else {
            // 如果不显示头像，添加一个空白占位符以保持布局一致
            JLabel spacerLabel = new JLabel(" ");
            spacerLabel.setPreferredSize(new Dimension(5, 24));
            avatarPanel.add(spacerLabel);
        }
        
        userInfoPanel.add(avatarPanel, BorderLayout.WEST);
            
                    // 用户名 - 不显示管理员标记，使用更隐蔽的颜色
        String displayName = username;  // 不再添加[管理员]前缀
        JLabel nameLabel = new JLabel(displayName);
        // 使用更隐蔽的颜色，避免红色等醒目颜色
        nameLabel.setForeground(isAdmin ? new Color(70, 110, 126) : new Color(90, 120, 140));
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
            
                    // 提取消息中的图片URL
        List<String> imageUrls = new ArrayList<>();
        if (message.contains("[img]")) {
            Pattern pattern = Pattern.compile("\\[img\\]([^\\[]+)\\[\\/img\\]");
            java.util.regex.Matcher matcher = pattern.matcher(message);
            while (matcher.find()) {
                imageUrls.add(matcher.group(1));
            }
        }
        
        // 创建内容面板
        JPanel messageContentPanel = new JPanel();
        messageContentPanel.setLayout(new BoxLayout(messageContentPanel, BoxLayout.Y_AXIS));
        messageContentPanel.setOpaque(false);
        
        // 处理纯文本部分（替换掉图片标签）
        String textContent = message.replaceAll("\\[img\\][^\\[]+\\[\\/img\\]", "").trim();
        
        // 如果有文本内容，添加文本标签
        if (!textContent.isEmpty()) {
            JLabel textLabel = new JLabel("<html><div style='width: 100%; line-height: 1.4;'>" + 
                    textContent.replace("\n", "<br>").replace("<", "&lt;").replace(">", "&gt;") + "</div></html>");
            textLabel.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 5));
            textLabel.setForeground(UIManager.getColor("Label.foreground"));
            textLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            messageContentPanel.add(textLabel);
        }
        
        // 处理图片
        if (!imageUrls.isEmpty()) {
            for (String imageUrl : imageUrls) {
                // 创建图片面板
                JPanel imagePanel = new JPanel();
                imagePanel.setLayout(new BoxLayout(imagePanel, BoxLayout.Y_AXIS));
                imagePanel.setOpaque(false);
                imagePanel.setAlignmentX(Component.LEFT_ALIGNMENT);
                imagePanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
                
                // 创建图片链接按钮
                JButton imageLink = new JButton("[图片]");
                imageLink.setForeground(new Color(59, 130, 246));
                imageLink.setBorderPainted(false);
                imageLink.setContentAreaFilled(false);
                imageLink.setFocusPainted(false);
                imageLink.setCursor(new Cursor(Cursor.HAND_CURSOR));
                imageLink.setAlignmentX(Component.LEFT_ALIGNMENT);
                
                // 创建图片标签（初始隐藏）
                JLabel imageLabel = new JLabel();
                imageLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
                imageLabel.setVisible(config.isShowImages()); // 根据设置决定初始状态
                
                // 异步加载图片
                new Thread(() -> {
                    try {
                        URL url = new URL(imageUrl);
                        BufferedImage originalImage = ImageIO.read(url);
                        
                        if (originalImage != null) {
                            // 调整图片大小，最大宽度200像素
                            int originalWidth = originalImage.getWidth();
                            int originalHeight = originalImage.getHeight();
                            int newWidth = Math.min(originalWidth, 200);
                            int newHeight = (int) ((double) originalHeight / originalWidth * newWidth);
                            
                            // 创建缩放后的图片
                            Image scaledImage = originalImage.getScaledInstance(newWidth, newHeight, Image.SCALE_SMOOTH);
                            ImageIcon icon = new ImageIcon(scaledImage);
                            
                            SwingUtilities.invokeLater(() -> {
                                imageLabel.setIcon(icon);
                                imagePanel.revalidate();
                                imagePanel.repaint();
                            });
                        }
                    } catch (Exception e) {
                        LOG.warn("无法加载图片: " + e.getMessage());
                        SwingUtilities.invokeLater(() -> {
                            imageLabel.setText("无法加载图片");
                        });
                    }
                }).start();
                
                // 添加点击事件，切换图片显示/隐藏
                imageLink.addActionListener(e -> {
                    boolean visible = !imageLabel.isVisible();
                    imageLabel.setVisible(visible);
                    imagePanel.revalidate();
                    imagePanel.repaint();
                });
                
                // 添加到图片面板
                imagePanel.add(imageLink);
                imagePanel.add(imageLabel);
                
                // 添加到消息内容面板
                messageContentPanel.add(imagePanel);
            }
        }
        
        contentPanel.add(messageContentPanel, BorderLayout.CENTER);
            
            // 添加组件到消息卡片
            messageCard.add(userInfoPanel, BorderLayout.NORTH);
            messageCard.add(contentPanel, BorderLayout.CENTER);
            
            // 添加到消息面板
            chatMessagesPanel.add(messageCard);
            chatMessagesPanel.add(Box.createVerticalStrut(8)); // 添加间距

            // 不在这里调用revalidate和repaint，改用批量更新UI的方法
            updateUILater();
            
            // 记录日志
            LOG.info("添加消息卡片: " + username + " - " + (message.length() > 20 ? message.substring(0, 20) + "..." : message));
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
        
        // 如果已经设置了pendingUIUpdate标志为true，说明是在批量加载历史消息
        // 此时不触发立即更新，而是等待批量加载完成后一次性更新
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
     * 如果滚动条已经在底部或接近底部，则自动滚动到新内容
     * 如果用户已经向上滚动查看历史消息，则不自动滚动
     */
    private boolean shouldScrollToBottom = true;
    
    /**
     * 检查滚动条是否在底部或接近底部
     * @return 如果滚动条在底部或接近底部，返回true
     */
    private boolean isScrollNearBottom() {
        JScrollBar scrollBar = chatScrollPane.getVerticalScrollBar();
        int value = scrollBar.getValue();
        int extent = scrollBar.getVisibleAmount();
        int maximum = scrollBar.getMaximum();
        int bottomPosition = maximum - extent;
        
        // 如果滚动条位置在最大值减去30像素以内，认为是接近底部
        boolean nearBottom = (bottomPosition - value <= 30);
        
        LOG.info("检查滚动条位置: value=" + value + ", max=" + maximum + 
                ", extent=" + extent + ", 距底部=" + (bottomPosition - value) + 
                ", 接近底部=" + nearBottom);
        
        return nearBottom;
    }
    
    /**
     * 滚动到底部
     * @param force 是否强制滚动到底部，忽略shouldScrollToBottom标志
     */
    private void scrollToBottom(boolean force) {
        // 使用多次延迟执行，确保在所有布局更新后滚动到底部
        SwingUtilities.invokeLater(() -> {
            if (force || shouldScrollToBottom) {
                JScrollBar vertical = chatScrollPane.getVerticalScrollBar();
                
                // 第一次尝试滚动到底部
                vertical.setValue(vertical.getMaximum());
                
                // 再次尝试滚动，确保布局更新后滚动到底部
                SwingUtilities.invokeLater(() -> {
                    vertical.setValue(vertical.getMaximum());
                    
                    // 第三次尝试，再次确保滚动到最底部
                    SwingUtilities.invokeLater(() -> {
                        // 使用Timer延迟100ms再次尝试，这时布局应该已经完全更新
                        Timer scrollTimer = new Timer(100, e -> {
                            vertical.setValue(vertical.getMaximum());
                            ((Timer)e.getSource()).stop();
                        });
                        scrollTimer.setRepeats(false);
                        scrollTimer.start();
                    });
                });
            }
        });
    }
    
    /**
     * 滚动到底部（使用默认行为，根据shouldScrollToBottom标志决定）
     */
    private void scrollToBottom() {
        scrollToBottom(false);
    }
    
    /**
     * 设置连接状态
     */
    public void setConnectionStatus(boolean connected, String statusText) {
        statusLabel.setText(statusText);
        if (connected) {
            statusLabel.setForeground(new Color(100, 120, 100)); // 暗绿色，更加隐蔽
        } else {
            statusLabel.setForeground(new Color(120, 120, 120)); // 灰色，更加隐蔽
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
