package com.github.yuyuanweb.mianshiyaplugin.toolWindow;

import com.github.yuyuanweb.mianshiyaplugin.constant.KeyConstant;
import com.github.yuyuanweb.mianshiyaplugin.view.HotNewsListPanel;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ex.ToolWindowManagerListener;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import com.intellij.openapi.application.ApplicationManager;

import javax.swing.*;
import java.awt.*;

/**
 * 主工具窗口工厂
 * 实现DumbAware接口，使工具窗口在索引构建时也可用
 */
public class MyToolWindowFactory implements ToolWindowFactory, DumbAware {
    private static final Logger LOG = Logger.getInstance(MyToolWindowFactory.class);
    
    // 静态组件引用，用于保持状态
    private static JComponent mainComponent = null;
    private static HotNewsListPanel hotNewsListPanel = null;
    
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        LOG.info("创建摸鱼岛工具窗口内容");
        
        ContentManager contentManager = toolWindow.getContentManager();
        
        // 如果已经有内容，不再创建
        if (contentManager.getContentCount() > 0) {
            LOG.info("工具窗口已有内容，跳过创建");
            return;
        }
        
        // 如果已经有组件实例，重用它
        if (mainComponent != null) {
            LOG.info("重用已有组件实例");
            ContentFactory contentFactory = ApplicationManager.getApplication().getService(ContentFactory.class);
            Content content = contentFactory.createContent(mainComponent, "摸鱼岛", false);
            content.setCloseable(false);
            contentManager.addContent(content);
            return;
        }
        
        // 创建主面板
        JBPanel<?> mainPanel = new JBPanel<>(new BorderLayout());
        mainPanel.setBorder(JBUI.Borders.empty());

        // 创建分割面板
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setDividerLocation(150);
        splitPane.setDividerSize(3);

        // 添加热榜列表面板 - 使用单例模式
        hotNewsListPanel = HotNewsListPanel.getInstance(project);
        splitPane.setRightComponent(hotNewsListPanel);

        // 添加分割面板到主面板
        mainPanel.add(splitPane, BorderLayout.CENTER);
        
        // 保存组件引用
        mainComponent = mainPanel;

        // 创建内容
        ContentFactory contentFactory = ApplicationManager.getApplication().getService(ContentFactory.class);
        Content content = contentFactory.createContent(mainPanel, "摸鱼岛", false);
        content.setCloseable(false);
        
        // 设置内容不会被自动释放
        content.setDisposer(() -> {
            // 空实现，防止内容被释放
            LOG.info("摸鱼岛内容被请求释放，但我们阻止了");
        });

        // 添加内容到工具窗口
        contentManager.addContent(content);
        
        // 添加工具窗口监听器
        project.getMessageBus().connect().subscribe(ToolWindowManagerListener.TOPIC, new ToolWindowManagerListener() {
            @Override
            public void stateChanged() {
                // 工具窗口状态改变时的处理
                LOG.info("摸鱼岛工具窗口状态改变");
            }
        });
    }
    
    @Override
    public boolean shouldBeAvailable(@NotNull Project project) {
        // 总是可用，即使在索引构建时
        return true;
    }
    
    @Override
    public boolean isDoNotActivateOnStart() {
        // 启动时不自动激活
        return true;
    }
}
