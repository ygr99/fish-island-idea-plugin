package com.github.yuyuanweb.mianshiyaplugin.config;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;

/**
 * 摸鱼室配置管理
 */
public class FishChatConfig {
    private static final String PREFIX = "fishChat.";
    private static final String TOKEN_KEY = PREFIX + "token";
    private static final String SHOW_AVATAR_KEY = PREFIX + "showAvatar";
    private static final String SHOW_IMAGES_KEY = PREFIX + "showImages";
    
    private final PropertiesComponent propertiesComponent;
    
    public FishChatConfig(Project project) {
        // 使用项目级配置
        propertiesComponent = PropertiesComponent.getInstance(project);
    }
    
    /**
     * 获取Token
     */
    public String getToken() {
        return propertiesComponent.getValue(TOKEN_KEY, "");
    }
    
    /**
     * 设置Token
     */
    public void setToken(String token) {
        propertiesComponent.setValue(TOKEN_KEY, token);
    }
    
    /**
     * 是否显示头像
     */
    public boolean isShowAvatar() {
        return propertiesComponent.getBoolean(SHOW_AVATAR_KEY, true);
    }
    
    /**
     * 设置是否显示头像
     */
    public void setShowAvatar(boolean showAvatar) {
        propertiesComponent.setValue(SHOW_AVATAR_KEY, showAvatar);
    }
    
    /**
     * 是否显示图片
     */
    public boolean isShowImages() {
        return propertiesComponent.getBoolean(SHOW_IMAGES_KEY, true);
    }
    
    /**
     * 设置是否显示图片
     */
    public void setShowImages(boolean showImages) {
        propertiesComponent.setValue(SHOW_IMAGES_KEY, showImages);
    }
} 