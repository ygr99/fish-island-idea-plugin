package com.github.yuyuanweb.mianshiyaplugin.view;

import com.intellij.openapi.project.Project;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.components.JBPanel;
import com.intellij.util.ui.JBUI;
import com.intellij.openapi.Disposable;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.handler.CefLifeSpanHandler;

import java.awt.*;

/**
 * 热榜新闻预览面板
 */
public class HotNewsPreviewPanel extends JBPanel<HotNewsPreviewPanel> implements Disposable {

    private final Project project;
    private final JBCefBrowser browser;
    private static final String FISH_URL = "https://fish.codebug.icu/";

    public HotNewsPreviewPanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        this.setBorder(JBUI.Borders.empty(10));

        // 创建浏览器组件
        browser = new JBCefBrowser();
        
        // 添加页面加载处理器
        browser.getJBCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadingStateChange(CefBrowser browser, boolean isLoading, boolean canGoBack, boolean canGoForward) {
                if (!isLoading) {
                    // 页面加载完成后注入修复脚本
                    if (browser.getURL().startsWith(FISH_URL)) {
                        injectFixScript(browser);
                    }
                }
            }
        }, browser.getCefBrowser());

        this.add(browser.getComponent(), BorderLayout.CENTER);
        
        // 初始化默认内容
        clearContent();
    }

    /**
     * 注入修复脚本
     */
    private void injectFixScript(CefBrowser browser) {
        StringBuilder scriptBuilder = new StringBuilder();
        scriptBuilder.append("(function() {")
                // 修复滚动问题
                .append("const originalScrollIntoView = Element.prototype.scrollIntoView;")
                .append("Element.prototype.scrollIntoView = function(options) {")
                .append("    originalScrollIntoView.call(this, options);")
                .append("    setTimeout(() => {")
                .append("        if (this.parentElement) {")
                .append("            this.parentElement.scrollTop = this.parentElement.scrollHeight;")
                .append("        }")
                .append("    }, 100);")
                .append("};")
                
                // 增强 DOM 更新监听
                .append("const observer = new MutationObserver((mutations) => {")
                .append("    mutations.forEach((mutation) => {")
                .append("        if (mutation.type === 'childList' || mutation.type === 'characterData') {")
                .append("            const messageContainer = document.querySelector('.message-list');")
                .append("            if (messageContainer) {")
                .append("                messageContainer.scrollTop = messageContainer.scrollHeight;")
                .append("            }")
                .append("        }")
                .append("    });")
                .append("});")
                
                // 监听整个文档的变化
                .append("observer.observe(document.body, {")
                .append("    childList: true,")
                .append("    subtree: true,")
                .append("    characterData: true")
                .append("});")
                
                // 修复撤回消息的更新
                .append("const originalRemoveChild = Element.prototype.removeChild;")
                .append("Element.prototype.removeChild = function(child) {")
                .append("    const result = originalRemoveChild.call(this, child);")
                .append("    const event = new CustomEvent('messageRemoved', {")
                .append("        detail: { element: child }")
                .append("    });")
                .append("    document.dispatchEvent(event);")
                .append("    return result;")
                .append("};")
                
                // 监听消息撤回事件
                .append("document.addEventListener('messageRemoved', () => {")
                .append("    const messageList = document.querySelector('.message-list');")
                .append("    if (messageList) {")
                .append("        messageList.style.display = 'none';")
                .append("        setTimeout(() => {")
                .append("            messageList.style.display = '';")
                .append("        }, 0);")
                .append("    }")
                .append("});")
                .append("})();");
            
        browser.executeJavaScript(scriptBuilder.toString(), browser.getURL(), 0);
    }

    /**
     * 加载URL
     */
    public void loadUrl(String url) {
        if (url != null && !url.isEmpty()) {
            browser.loadURL(url);
        }
    }
    
    /**
     * 加载URL并在加载完成后执行回调
     */
    public void loadUrl(String url, Runnable onLoadFinished) {
        if (url != null && !url.isEmpty()) {
            browser.getJBCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
                @Override
                public void onLoadingStateChange(CefBrowser browser, boolean isLoading, boolean canGoBack, boolean canGoForward) {
                    if (!isLoading) {
                        onLoadFinished.run();
                        browser.getClient().removeLoadHandler();
                    }
                }
            }, browser.getCefBrowser());
            browser.loadURL(url);
        }
    }
    
    /**
     * 清空内容
     */
    public void clearContent() {
        StringBuilder htmlBuilder = new StringBuilder();
        htmlBuilder.append("<html><body style='margin:0;padding:20px;font-family:Arial,sans-serif;color:#666;'>")
                .append("<div style='text-align:center;padding-top:100px;'>")
                .append("<h2 style='color:#333;margin-bottom:20px;'>请选择一条新闻查看详情</h2>")
                .append("<p style='color:#999;'>点击左侧列表中的新闻即可查看详细内容</p>")
                .append("</div></body></html>");
        browser.loadHTML(htmlBuilder.toString());
    }
    
    @Override
    public void dispose() {
        browser.dispose();
    }
} 