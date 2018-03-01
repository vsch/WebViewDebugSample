package com.vladsch.javafx.webview.debugger;

import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.concurrent.Worker;
import javafx.geometry.HPos;
import javafx.geometry.VPos;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.text.FontSmoothingType;
import javafx.scene.web.WebHistory;
import javafx.scene.web.WebView;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.events.EventListener;
import org.w3c.dom.events.EventTarget;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.function.Consumer;

class Browser extends Region {
    final WebView myWebView = new WebView();
    final WebView myMessageView = new WebView();
    TextField locationField;
    BorderPane myBorderPane;
    StringBuilder myMessageBuilder = new StringBuilder();
    int myMessageCount = 0;
    Runnable myOnPageLoadRunnable = null;

    /* *****************************************************************************************
     *  Required: JSBridge to handle debugging proxy interface
     *******************************************************************************************/
    final DevToolsDebuggerJsBridge myJSBridge;

    /* *****************************************************************************************
     *  Optional state provider to allow persistent state for scripts
     *******************************************************************************************/
    final JfxScriptStateProvider myStateProvider;

    /* *****************************************************************************************
     *  Optional: extend JSBridge, or use an instance of DevToolsDebuggerJsBridge
     *******************************************************************************************/
    public class DevToolsJsBridge extends DevToolsDebuggerJsBridge {
        public DevToolsJsBridge(final @NotNull WebView webView, final int instance, @Nullable final JfxScriptStateProvider stateProvider) {
            super(webView, instance, stateProvider);
        }
    }

    /* *****************************************************************************************
     *  Required to call pageReloading() to inform of upcoming WebView side page reload
     *******************************************************************************************/
    public void load(final String url) {
        // let it know that we are reloading the page, not chrome dev tools
        myJSBridge.pageReloading();
        // load the web page
        myWebView.getEngine().load(url);
    }

    /* *****************************************************************************************
     *  Required JSBridge connection to JavaScript
     *  Called on page loading SUCCESS
     *  does not need to be a separate method, only brought out for illustration purposes
    *******************************************************************************************/
    private void connectJSBridge() {
        myJSBridge.connectJsBridge();
    }

    /* *****************************************************************************************
     *  Required: need to add JSBridge helper script in the head to setup missing console for other scripts to use
     *  Optionally insert persisted JavaScript state information into the page
     *******************************************************************************************/
    private String instrumentHtml(String html) {
        // now we add our script if not debugging, because it will be injected
        if (!myJSBridge.isDebugging()) {
            html = html.replace("<head>", "<head>\n<script src=\"markdown-navigator.js\"></script>");
        }
        // inject the state if it exists
        if (!myStateProvider.getState().isEmpty()) {
            html = html.replace("<body>", "<body>\n<script>\n" + myJSBridge.getStateString() + "\n</script>");
        }
        return html;
    }

    /* *****************************************************************************************
     *  Required: to start/stop debugging connection to this web view instance
     *******************************************************************************************/
    private void toggleDebugging(Consumer<Throwable> onStartFail, Runnable onStartSuccess, Consumer<Boolean> onStop) {
        if (myJSBridge.isDebuggerEnabled()) {
            myJSBridge.stopDebugServer(onStop);
        } else {
            myJSBridge.startDebugServer(getPort(), onStartFail, onStartSuccess);
        }
    }

    /* *****************************************************************************************
     *
     *  The rest is there to provide basic WebView Debug Sample to allow playing
     *  with the debugger. Not dictated by the JavaFX WebView Debugger requirements
     *
     *******************************************************************************************/
    public BorderPane getRootPane() {
        return myBorderPane;
    }

    public Browser(JfxScriptStateProvider stateProvider) {
        locationField = new TextField(Browser.getURL(Browser.getReadmeFile()));
        locationField.textProperty().bind(myWebView.getEngine().locationProperty());
        locationField.setOnAction(e -> {
            load(getUrl(locationField.getText()));
        });

        myMessageView.setMaxHeight(100);
        myBorderPane = new BorderPane(this, locationField, null, myMessageView, null);

        myStateProvider = stateProvider;

        // create JSBridge Instance
        myJSBridge = new DevToolsJsBridge(myWebView, 0, myStateProvider);

        //apply the styles
        getStyleClass().add("browser");

        //add the web view to the scene
        getChildren().add(myWebView);

        myWebView.setContextMenuEnabled(false);
        createContextMenu();

        // process page loading
        myWebView.getEngine().getLoadWorker().stateProperty().addListener(
                (ov, oldState, newState) -> {
                    if (newState == Worker.State.SUCCEEDED) {
                        connectJSBridge();

                        // here we can register listeners for events but need to let JS handle theirs without interference
                        // it seems js event.preventDefault() and .stopImmediatePropagation() don't work on Java registered events
                        EventListener clickListener = evt -> {
                            if (myJSBridge.getJSEventHandledBy() != null) {
                                addMessage("warn", "onClick: default prevented by: " + myJSBridge.getJSEventHandledBy());
                                myJSBridge.clearJSEventHandledBy();
                            } else {
                                Node element = (Node) evt.getTarget();
                                Node id = element.getAttributes().getNamedItem("id");
                                String idSelector = id != null ? "#" + id.getNodeValue() : "";
                                addMessage("onClick: clicked on " + element.getNodeName() + idSelector);
                            }
                        };

                        Document document = myWebView.getEngine().getDocument();
                        ((EventTarget) document).addEventListener("click", clickListener, false);

                        if (myOnPageLoadRunnable != null) {
                            myOnPageLoadRunnable.run();
                        }
                    }
                });

        myWebView.getEngine().getLocation();
        loadStartPage();
    }

    private void addMessage(String message) {
        addMessage("log", message);
    }

    private void addMessage(String type, String message) {
        myMessageCount++;
        String countedMessage = "[" + myMessageCount + "]" + message;
        System.out.println(countedMessage);
        String suffix = "</span><br/>\n";
        String prefix;

        switch (type) {
            case "warn":
                prefix = "<span class='msg warn'>";
                break;
            case "error":
                prefix = "<span class='msg error'>";
                break;
            case "debug":
                prefix = "<span class='msg debug'>";
                break;
            default:
            case "log":
                prefix = "<span class='msg'>";
                break;
        }

        Platform.runLater(() -> {
            File messageFile = new File("public/messages.html");
            myMessageBuilder.append(prefix).append("<span class='msgCount'>[").append(myMessageCount).append("]</span> ").append(message).append(suffix);
            try (FileWriter writer = new FileWriter(messageFile)) {
                writer.write(
                        "<head>\n" +
                                "<html>\n" +
                                "<head>\n" +
                                "<meta charset=\"UTF-8\">\n" +
                                "<link rel=\"stylesheet\" href=\"layout-fx.css\">\n" +
                                "<link rel=\"stylesheet\" href=\"default-fx.css\">" +
                                "<style>\n" +
                                ".container { width: 100%; }\n" +
                                ".markdown-body { line-height:1.1; }\n" +
                                ".msg { font-size: 0.7em; font-family:Consolas,Inconsolata,Courier,monospace; margin: 0; padding:0; }\n" +
                                ".warn { background-color: hsl(50, 95%, 90%); }\n" +
                                ".error { color: #CC0040; background-color: hsl(0, 95%, 95%); font-weight:bold; }\n" +
                                ".debug { color: #999999 }\n" +
                                ".msgCount { display:inline-block; padding:0; width:30px;text-align:right;}\n" +
                                "</style>\n" +
                                "</head>\n" +
                                "<body>\n" +
                                "<article class=\"markdown-body\">");
                writer.write(myMessageBuilder.toString());
                writer.write(
                        "</article>\n" +
                                "<script>\n" +
                                " window.scrollTo(window.pageXOffset, 100000000);\n" +
                                "</script>\n" +
                                "</body>\n" +
                                "</html>\n");
                writer.flush();
                writer.close();

                myMessageView.getEngine().load(getURL(messageFile));

                if (myJSBridge.isDebugging()) {
                    myWebView.getEngine().executeScript(String.format("console.%s(\"%s\");", type, message));
                }
            } catch (Exception ignored) {

            }
        });
    }

    private String getUrl(String text) {
        if (!text.contains("://")) {
            return "http://" + text;
        } else {
            return text;
        }
    }

    public static String getURL(final File publicFile) {
        try {
            return String.valueOf(publicFile.toURI().toURL());
        } catch (MalformedURLException e) {
            return String.valueOf(publicFile.toURI()).replace("file:/", "file:///");
        }
    }

    void loadStartPage() {
        // if not debugging then need to instrument the file
        File htmlFile = getReadmeFile();

        // copy the HTML sample to app/public directory so we can change its content dynamically to reflect saved state
        // and inject JSBridge helper when the debugger is not connected
        try {
            FileWriter fileWriter = new FileWriter(htmlFile);
            WebViewDebugSample.copy(WebViewDebugSample.class.getResourceAsStream("/README.html"), fileWriter, this::instrumentHtml);
            fileWriter.close();
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
        }

        updateViewOptions();
        String url = getURL(htmlFile);
        load(url);
    }

    @NotNull
    public static File getReadmeFile() {
        return new File("public/README.html");
    }

    void updateViewOptions() {
        FontSmoothingType typeToSet;
        if (isGrayScaleSmoothing()) {
            typeToSet = FontSmoothingType.GRAY;
        } else {
            typeToSet = FontSmoothingType.LCD;
        }

        ObjectProperty<FontSmoothingType> fontSmoothingTypeProperty = myWebView.fontSmoothingTypeProperty();
        if (fontSmoothingTypeProperty.get() != typeToSet) {
            fontSmoothingTypeProperty.setValue(typeToSet);
        }

        double zoom = myStateProvider.getState().getJsNumber("zoomFactor").doubleValue(1.0);
        if (myWebView.getZoom() != zoom) {
            myWebView.setZoom(zoom);
        }
    }

    void updateHistoryButtons(MenuItem goBack, MenuItem goForward) {
        WebHistory history = myWebView.getEngine().getHistory();
        goBack.setDisable(history.getCurrentIndex() == 0);
        goForward.setDisable(history.getCurrentIndex() + 1 >= history.getEntries().size());
    }

    private boolean isGrayScaleSmoothing() {
        return myStateProvider.getState().getJsNumber("useGrayScaleSmoothing").isTrue();
    }

    private void updatePortMenu(Menu debugPort) {
        debugPort.setText("Port: " + getPort());
        debugPort.getItems().get(0).setText("Change to: " + (getPort() - 1));
        debugPort.getItems().get(1).setText("Change to: " + (getPort() + 1));
    }

    private void createContextMenu() {
        ContextMenu contextMenu = new ContextMenu();
        MenuItem reload = new MenuItem("Reload");
        reload.setOnAction(e -> myWebView.getEngine().reload());

        MenuItem goBack = new MenuItem("Go Back");
        goBack.setOnAction(e -> {
            WebHistory history = myWebView.getEngine().getHistory();
            Platform.runLater(() -> {
                history.go(-1);
            });
        });

        MenuItem goForward = new MenuItem("Go Forward");
        goForward.setOnAction(e -> {
            WebHistory history = myWebView.getEngine().getHistory();
            Platform.runLater(() -> {
                history.go(1);
            });
        });

        myOnPageLoadRunnable = ()->{
            updateHistoryButtons(goBack, goForward);
        };

        Menu debugPort = new Menu("");
        MenuItem decrementPort = new MenuItem("");
        MenuItem incrementPort = new MenuItem("");
        debugPort.getItems().addAll(decrementPort, incrementPort);
        updatePortMenu(debugPort);

        decrementPort.setOnAction(e -> {
            setPort(getPort() - 1);
            updatePortMenu(debugPort);
        });
        incrementPort.setOnAction(e -> {
            setPort(getPort() + 1);
            updatePortMenu(debugPort);
        });

        MenuItem copyDebugUrl = new MenuItem("Copy Debug Server URL");
        copyDebugUrl.setOnAction(e -> {
            // from: https://stackoverflow.com/questions/6710350/copying-text-to-the-clipboard-using-java
            StringSelection stringSelection = new StringSelection(myJSBridge.getDebuggerURL());
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(stringSelection, null);
        });
        copyDebugUrl.setDisable(true);

        MenuItem debuggingEnabled = new MenuItem("Start Debugging");

        Runnable updateDebugOn = () -> {
            debuggingEnabled.setText("Stop Debug Server");
            copyDebugUrl.setDisable(false);
            debugPort.setDisable(true);
        };

        Runnable updateDebugOff = () -> {
            debuggingEnabled.setText("Start Debug Server");
            copyDebugUrl.setDisable(true);
            debugPort.setDisable(false);
        };

        debuggingEnabled.setOnAction(e -> {
            toggleDebugging((ex) -> {
                // failed to start
                addMessage("error", "Debug server failed to start: " + ex.getMessage());
                updateDebugOff.run();
            }, () -> {
                addMessage("Debug server started, debug URL: " + myJSBridge.getDebuggerURL());
                // can copy debug URL to clipboard
                updateDebugOn.run();
            }, value -> {
                // true - stopped and shutdown
                // false - stopped and still running for other instances
                // null - was not connected so don't know
                if (value == null) {
                    addMessage("Debug server stopped");
                } else if (value) {
                    addMessage("Debug server shutdown");
                } else {
                    addMessage("Debug server connection closed");
                }
                updateDebugOff.run();
            });
        });

        contextMenu.getItems().addAll(reload, goBack, goForward, debugPort, debuggingEnabled, copyDebugUrl);

        myWebView.setOnMousePressed(e -> {
            if (e.getButton() == MouseButton.SECONDARY) {
                contextMenu.show(myWebView, e.getScreenX(), e.getScreenY());
            } else {
                contextMenu.hide();
            }
        });
    }

    private int getPort() {
        return myStateProvider.getState().getJsNumber("debugPort").intValue(51723);
    }

    private void setPort(int port) {
        myStateProvider.getState().put("debugPort", port);
    }

    @Override
    protected void layoutChildren() {
        double w = getWidth();
        double h = getHeight();
        layoutInArea(myWebView, 0, 0, w, h, 0, HPos.CENTER, VPos.CENTER);
    }

    @Override
    protected double computePrefWidth(double height) {
        return 750;
    }

    @Override
    protected double computePrefHeight(double width) {
        return 800;
    }
}
