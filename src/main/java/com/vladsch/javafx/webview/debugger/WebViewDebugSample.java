package com.vladsch.javafx.webview.debugger;

import com.vladsch.boxed.json.BoxedJsObject;
import com.vladsch.boxed.json.BoxedJson;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.util.function.Function;

public class WebViewDebugSample extends Application implements JfxScriptStateProvider {

    private Scene scene;
    static private BoxedJsObject ourJsState = BoxedJson.of(); // start with empty state

    @NotNull
    @Override
    public BoxedJsObject getState() {
        return ourJsState;
    }

    @Override
    public void setState(@NotNull final BoxedJsObject state) {
        ourJsState = state;
    }

    // context menu code from: https://stackoverflow.com/questions/27047447/customized-context-menu-on-javafx-webview-webengine
    @Override
    public void start(Stage stage) {
        Browser browser = new Browser(this);

        // create the scene
        stage.setTitle("JavaFX WebView Debugger Sample");
        scene = new Scene(browser.getRootPane(), 750, 800, Color.web("#ffffff"));
        stage.setScene(scene);
        stage.show();
    }

    @Override
    public void stop() throws Exception {
        // write out the script state for the page
        File stateFile = new File("WebViewDebugSample.json");
        if (ourJsState.isEmpty()) {
            stateFile.delete();
        } else {
            // save state for next run
            try {
                FileWriter stateWriter = new FileWriter(stateFile);
                stateWriter.write(ourJsState.toString());
                stateWriter.flush();
                stateWriter.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        System.exit(0);
    }

    @Override
    public void init() throws Exception {
        super.init();

        // load previously stored state for the page
        try {
            File stateFile = new File("WebViewDebugSample.json");
            if (stateFile.exists()) {
                // read in the previous state
                FileReader stateReader = new FileReader(stateFile);
                ourJsState = BoxedJson.boxedFrom(stateReader);
                stateReader.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (!ourJsState.isValid()) {
            ourJsState = BoxedJson.of();
        }
    }

    public static void main(String[] args) {
        //Platform.setImplicitExit(false);
        File publicDir = new File("public");

        if (!publicDir.exists()) {
            publicDir.mkdir();
        }

        // copy resources to public for page display
        String[] resources = new String[] {
                "/default-fx.css",
                "/github-collapse.css",
                "/github-collapse-markdown.js",
                "/layout-fx.css",
                "/markdown-navigator.js",
                "/scroll-preview.js",
                "/README.html",
        };

        try {
            for (String resource : resources) {
                File publicFile = new File(publicDir.getAbsolutePath() + resource);
                FileWriter fileWriter = new FileWriter(publicFile);
                copy(WebViewDebugSample.class.getResourceAsStream(resource), fileWriter);
                fileWriter.close();
            }

            // copy binary font file
            InputStream inputStream = WebViewDebugSample.class.getResourceAsStream("/taskitems.ttf");
            FileOutputStream outputStream = new FileOutputStream(publicDir.getAbsolutePath() + "/taskitems.ttf");
            copyBinary(inputStream, outputStream);
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
        }

        launch(args);
    }

    // Resource to local file
    public static void copy(final InputStream inputStream, final Writer writer, @NotNull Function<String, String> modifier) throws IOException {
        InputStreamReader reader = new InputStreamReader(inputStream);
        copy(reader, writer, modifier);
        inputStream.close();
    }

    public static void copy(final Reader reader, final Writer writer, @NotNull Function<String, String> modifier) throws IOException {
        StringWriter stringWriter = new StringWriter();
        copy(reader, stringWriter);
        stringWriter.close();
        String result = modifier.apply(stringWriter.toString());

        StringReader stringReader = new StringReader(result);
        copy(stringReader, writer);
        stringReader.close();
    }

    public static void copy(final InputStream inputStream, final Writer writer) throws IOException {
        InputStreamReader reader = new InputStreamReader(inputStream);
        copy(reader, writer);
        inputStream.close();
    }

    public static void copy(final Reader reader, final Writer writer) throws IOException {
        char[] buffer = new char[4096];
        int n;
        while (-1 != (n = reader.read(buffer))) {
            writer.write(buffer, 0, n);
        }
        writer.flush();
        reader.close();
    }

    public static void copyBinary(final InputStream reader, final OutputStream writer) throws IOException {
        byte[] buffer = new byte[4096];
        int n;
        while (-1 != (n = reader.read(buffer))) {
            writer.write(buffer, 0, n);
        }
        writer.flush();
        writer.close();
        reader.close();
    }
}

