/*
 *   The MIT License (MIT)
 *   <p>
 *   Copyright (c) 2018-2020 Vladimir Schneider (https://github.com/vsch)
 *   <p>
 *   Permission is hereby granted, free of charge, to any person obtaining a copy
 *   of this software and associated documentation files (the "Software"), to deal
 *   in the Software without restriction, including without limitation the rights
 *   to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *   copies of the Software, and to permit persons to whom the Software is
 *   furnished to do so, subject to the following conditions:
 *   <p>
 *   The above copyright notice and this permission notice shall be included in all
 *   copies or substantial portions of the Software.
 *   <p>
 *   THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *   IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *   FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *   AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *   LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *   OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *   SOFTWARE
 *
 */

package com.vladsch.javafx.webview.debugger;

import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.concurrent.Worker;
import javafx.geometry.HPos;
import javafx.geometry.Orientation;
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
    Runnable myOnConnectionChangeRunnable = null;

    /* *****************************************************************************************
     *  Required: JSBridge to handle debugging proxy interface
     *******************************************************************************************/
    final DevToolsDebuggerJsBridge myJSBridge;

    /* *****************************************************************************************
     *  Optional: state provider to allow persistent state for scripts
     *******************************************************************************************/
    final JfxScriptStateProvider myStateProvider;

    /* *****************************************************************************************
     *  Optional: extend JSBridge, or use an instance of DevToolsDebuggerJsBridge
     *******************************************************************************************/
    public class DevToolsJsBridge extends DevToolsDebuggerJsBridge {
        public DevToolsJsBridge(final @NotNull WebView webView, final int instance, @Nullable final JfxScriptStateProvider stateProvider) {
            super(webView, webView.getEngine(), instance, stateProvider);
        }

        // need these to update menu item state
        @Override
        public void onConnectionOpen() {
            addMessage("Chrome Dev Tools connected");
            if (myOnConnectionChangeRunnable != null) {
                myOnConnectionChangeRunnable.run();
            }
        }

        @Override
        public void onConnectionClosed() {
            addMessage("warn", "Chrome Dev Tools disconnected");
            if (myOnConnectionChangeRunnable != null) {
                myOnConnectionChangeRunnable.run();
            }
        }
    }

    /* *****************************************************************************************
     *  Required: to call JSBridge.pageReloading() to inform of upcoming WebView side page reload
     *******************************************************************************************/
    public void load(final String url) {
        // let it know that we are reloading the page, not chrome dev tools
        myJSBridge.pageReloading();
        // load the web page
        myWebView.getEngine().load(url);
    }

    /* *****************************************************************************************
     *  Optional: adds JSBridge helper script in the head to setup missing console for
     *            other scripts to use when a debugger is not connected
     *
     *  Optional: insert persisted JavaScript state information into the page
     *******************************************************************************************/
    private String instrumentHtml(String html) {
        // now we add our script if not debugging, because it will be injected
        if (!myJSBridge.isDebugging()) {
            html = html.replace("<head>", "<head>\n<script src=\"markdown-navigator.js\"></script>");
        }
        // inject the state if it exists
        if (myStateProvider != null && !myStateProvider.getState().isEmpty()) {
            html = html.replace("<body>", "<body>\n<script>\n" + myJSBridge.getStateString() + "\n</script>");
        }
        return html;
    }

    /* *****************************************************************************************
     *  Required: to start debugging connection to this web view instance
     *******************************************************************************************/
    private void startDebugging(Consumer<Throwable> onStartFail, Runnable onStartSuccess) {
        if (!myJSBridge.isDebuggerEnabled()) {
            int port = getPort();
            myJSBridge.startDebugServer(port, onStartFail, onStartSuccess);
        }
    }

    /* *****************************************************************************************
     *  Required: to stop debugging connection to this web view instance
     *******************************************************************************************/
    private void stopDebugging(Consumer<Boolean> onStop) {
        if (myJSBridge.isDebuggerEnabled()) {
            myJSBridge.stopDebugServer(onStop);
        }
    }

    /* *****************************************************************************************
     *  Required: to establish a JSBridge connection to JavaScript, called on page loading SUCCEEDED
     *            does not need to be a separate method, only brought out for illustration purposes
     *            See myWebView.getEngine().getLoadWorker().stateProperty().addListener() in
     *            the Browser constructor
     *******************************************************************************************/
    private void connectJSBridge() {
        myJSBridge.connectJsBridge();
    }

    /* *****************************************************************************************
     *
     *  The rest is there to provide basic WebView Debug Sample to allow playing
     *  with the debugger. Not dictated by the JavaFX WebView Debugger requirements
     *
     *******************************************************************************************/
    public Browser(JfxScriptStateProvider stateProvider) {
        myStateProvider = stateProvider;

        // create JSBridge Instance
        myJSBridge = new DevToolsJsBridge(myWebView, 0, myStateProvider);

        locationField = new TextField(Browser.getURL(Browser.getReadmeFile()));
        locationField.setOnAction(e -> {
            load(getUrl(locationField.getText()));
        });

        //myMessageView.setMaxHeight(100);
        final SplitPane splitPane = new SplitPane();
        splitPane.getItems().addAll(this, myMessageView);
        splitPane.setOrientation(Orientation.VERTICAL);
        float splitPosition = 0.9f;

        if (myStateProvider != null) {
            splitPosition = myStateProvider.getState().evalFloat("splitPosition", splitPosition);
            myStateProvider.getState().put("splitPosition", splitPosition);
            splitPane.getDividers().get(0).positionProperty().addListener((observable, oldValue, newValue) -> {
                myStateProvider.getState().put("splitPosition", newValue.floatValue());
            });
        }
        splitPane.setDividerPositions(splitPosition);

        myBorderPane = new BorderPane(splitPane, locationField, null, null, null);

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
                        /* *****************************************************************************************
                         *  Required: to establish a JSBridge connection to JavaScript, called on page loading SUCCEEDED
                         *            does not need to be a separate method, only brought out for illustration purposes
                         *******************************************************************************************/
                        connectJSBridge();

                        /* *****************************************************************************************
                         *  Optional: JavaScript event.preventDefault(), event.stopPropagation() and
                         *            event.stopImmediatePropagation() don't work on Java registered listeners.
                         *            The alternative mechanism is for JavaScript event handler to set
                         *            markdownNavigator.setEventHandledBy("IdentifyingTextForDebugging")
                         *            Then in Java event listener to check for this value not being null,
                         *            and clear it for next use.
                         *******************************************************************************************/
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
                        locationField.setText(myWebView.getEngine().getLocation());

                        if (myOnPageLoadRunnable != null) {
                            myOnPageLoadRunnable.run();
                        }
                    }
                });

        loadStartPage();
    }

    void addMessage(String message) {
        addMessage("log", message);
    }

    void addMessage(String type, String message) {
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

                // cannot use script console.log until page is loaded, otherwise will mess up state machine for log handling
                //if (myJSBridge.isDebugging()) {
                //    myWebView.getEngine().executeScript(String.format("console.%s(\"%s\");", type, message));
                //}
            } catch (Exception ignored) {

            }
        });
    }

    public BorderPane getRootPane() {
        return myBorderPane;
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

        double zoom = getZoomFactor();
        if (myWebView.getZoom() != zoom) {
            myWebView.setZoom(zoom);
        }
    }

    void updateHistoryButtons(MenuItem goBack, MenuItem goForward) {
        WebHistory history = myWebView.getEngine().getHistory();
        goBack.setDisable(history.getCurrentIndex() == 0);
        goForward.setDisable(history.getCurrentIndex() + 1 >= history.getEntries().size());
    }

    private double getZoomFactor() {
        return myStateProvider != null ? myStateProvider.getState().getJsNumber("zoomFactor").doubleValue(1.0) : 1.0;
    }

    private boolean isGrayScaleSmoothing() {
        return myStateProvider != null && myStateProvider.getState().getJsNumber("useGrayScaleSmoothing").isTrue();
    }

    private void updatePortMenu(Menu debugPort) {
        int port = getPort();
        debugPort.setText("Port: " + port);
        debugPort.getItems().get(0).setText("Change to: " + (port - 1));
        debugPort.getItems().get(1).setText("Change to: " + (port + 1));
    }

    private void createContextMenu() {
        ContextMenu contextMenu = new ContextMenu();
        MenuItem reload = new MenuItem("Reload Page");
        reload.setOnAction(e -> {
            String location = myWebView.getEngine().getLocation();
            if (location.replace("file:///", "file:/").equals(getURL(getReadmeFile()))) {
                // use the url to reload, that way if debugging status changed the page content will be properly updated
                loadStartPage();
            } else {
                myJSBridge.reloadPage(false, false);
            }
        });

        MenuItem reloadAndPause = new MenuItem("Reload Page & Pause");
        reloadAndPause.setOnAction(e -> myJSBridge.reloadPage(true, false));

        // Caution: not for normal script debugging
        // this one debug breaks before injection code is run, continuing does not properly establish
        // jsBridge connection, only useful for debugging injected helper code
        // and to see internal implementation JavaScript commandLineAPI of WebEngine
        MenuItem reloadAndBreak = new MenuItem("Reload Page & Break");
        reloadAndBreak.setOnAction(e -> myJSBridge.reloadPage(false, true));
        reloadAndBreak.setVisible(false);

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

/*
        WebConsoleListener.setDefaultListener((webView, message, lineNumber, sourceId) -> {
            System.out.println(message + "[at " + lineNumber + "]");
        });
*/

        myOnPageLoadRunnable = () -> {
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
            reloadAndPause.setDisable(!myJSBridge.isDebugging());
            reloadAndBreak.setDisable(!myJSBridge.isDebugging());
        };

        Runnable updateDebugOff = () -> {
            debuggingEnabled.setText("Start Debug Server");
            copyDebugUrl.setDisable(true);
            debugPort.setDisable(false);
            reloadAndPause.setDisable(true);
            reloadAndBreak.setDisable(true);
        };

        myOnConnectionChangeRunnable = () -> {
            reloadAndPause.setDisable(!myJSBridge.isDebugging());
            reloadAndBreak.setDisable(!myJSBridge.isDebugging());
        };

        debuggingEnabled.setOnAction(e -> {
            if (myJSBridge.isDebuggerEnabled()) {
                stopDebugging(value -> {
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
            } else {
                startDebugging((ex) -> {
                    // failed to start
                    addMessage("error", "Debug server failed to start: " + ex.getMessage());
                    updateDebugOff.run();
                }, () -> {
                    addMessage("Debug server started, debug URL: " + myJSBridge.getDebuggerURL());
                    // can copy debug URL to clipboard
                    updateDebugOn.run();
                });
            }
        });

        contextMenu.getItems().addAll(reload, reloadAndPause, reloadAndBreak, goBack, goForward, debugPort, debuggingEnabled, copyDebugUrl);

        myWebView.setOnMousePressed(e -> {
            if (e.getButton() == MouseButton.SECONDARY) {
                contextMenu.show(myWebView, e.getScreenX(), e.getScreenY());
            } else {
                contextMenu.hide();
            }
        });

        updateDebugOff.run();
    }

    private int getPort() {
        assert myStateProvider != null;
        return myStateProvider.getState().getJsNumber("debugPort").intValue(51723);
    }

    private void setPort(int port) {
        assert myStateProvider != null;
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
