package com.github.addshore.facebook.data.image.exif;

import com.thebuzzmedia.exiftool.ExifTool;
import com.thebuzzmedia.exiftool.ExifToolBuilder;
import com.thebuzzmedia.exiftool.exceptions.UnsupportedFeatureException;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.apache.commons.io.FilenameUtils;

import javax.swing.*;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;


public class Main extends Application {

    private static final String POSTS_DIRECTORY = "your_facebook_activity/posts";
    private final String version = "0.13";
    private Stage stage;
    private MainView view;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) throws Exception {
        this.stage = stage;

        stage.setTitle("Facebook Data Image Exif Tool");
        Scene dataEntryScene = this.getDataEntryScene();

        //System.setProperty("exiftool.debug","True");

        stage.setScene(dataEntryScene);
        stage.show();
    }

    /**
     * Looks for an exiftool executable in the system PATH
     * where an exiftool executable would be any file that without an extension has the string name "exiftool"
     *
     * @return File
     */
    private File getExifToolFromPath() throws FileNotFoundException {
        for (String dirString : System.getenv("PATH").split(File.pathSeparator)) {
            File dir = new File(dirString);
            if (dir.isDirectory()) {
                for (File file : Objects.requireNonNull(dir.listFiles())) {
                    String fileWithoutExt = FilenameUtils.removeExtension(file.getName());
                    if (fileWithoutExt.equals("exiftool")) {
                        return file;
                    }
                }
            }
        }
        throw new FileNotFoundException();
    }

    /**
     * For development and testing only, checks for the existence of the facebook-example directory
     * for use with pre populating the input fields to save time
     *
     * @return File
     */
    private File getDevTestFacebookExport() throws FileNotFoundException {
        File possibleDevTestExample = new File(System.getProperty("user.dir") + File.separator + "facebook-example");
        if (possibleDevTestExample.exists()) {
            return possibleDevTestExample;
        }
        throw new FileNotFoundException();
    }

    private Boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("windows");
    }

    private Scene getDataEntryScene() throws Exception {
        view = new MainView();

        view.versionLabel.setText("Version: " + this.version);

        view.hyperLinkAddshore.setOnAction(t -> getHostServices().showDocument("https://addshore.com/redirects/exiftool/writtenbylink"));
        view.hyperLinkCoffee.setOnAction(t -> getHostServices().showDocument("https://addshore.com/redirects/exiftool/coffeelink"));
        view.hyperLinkExif.setOnAction(t -> getHostServices().showDocument("https://addshore.com/redirects/exiftool/exiftoollink"));

        if (isWindows()) {
            view.dirInput.setPromptText("Example: C:\\Users\\example\\downloads\\extracted-facebook-export");
            view.toolInput.setPromptText("Example: C:\\Users\\example\\downloads\\exiftool.exe");

        } else {
            view.dirInput.setPromptText("Example: /path/to/extracted-facebook-export");
            view.toolInput.setPromptText("Example: /usr/bin/exiftool");
        }

        // Try to pre fill the data input field with the test data location for development
        try {
            final File devTestExportPath = getDevTestFacebookExport();
            view.dirInput.setText(devTestExportPath.getAbsolutePath());
        } catch (FileNotFoundException ignored) {
        }

        // Try to pre fill the exiftool input with a value from PATH
        try {
            final File exifToolFromPath = getExifToolFromPath();
            view.toolInput.setText(exifToolFromPath.getAbsolutePath());
            view.toolLabel.setText(view.toolLabel.getText() + " (found in your PATH)");
        } catch (FileNotFoundException ignored) {
            view.toolLabel.setText(view.toolLabel.getText() + " (downloadable below)");
        }

        // Set default values for checkboxes
        view.processPostsCheckbox.setSelected(true);
        view.processMessagesCheckbox.setSelected(true);
        view.processFilesCheckbox.setSelected(false);
        
        view.runButton.setOnAction(this.getButtonClickEventHandler(false));
        view.dryRunButton.setOnAction(this.getButtonClickEventHandler(true));
        view.dirInputBrowse.setOnAction(this.getBrowseButtonClickEventHandler(view.dirInput, JFileChooser.DIRECTORIES_ONLY));
        view.toolInputBrowse.setOnAction(this.getBrowseButtonClickEventHandler(view.toolInput, JFileChooser.FILES_ONLY));

        return new Scene(view.dataEntryView, 700, 400);
    }

    private EventHandler<ActionEvent> getBrowseButtonClickEventHandler(TextField input, int selectionMode) {
        return t -> {
            JFileChooser chooser = new JFileChooser();

            // Add listener on chooser to detect changes to selected file
            chooser.addPropertyChangeListener(evt -> {
                if (JFileChooser.SELECTED_FILE_CHANGED_PROPERTY
                        .equals(evt.getPropertyName())) {
                    JFileChooser chooser1 = (JFileChooser) evt.getSource();
                    try {
                        File curFile = chooser1.getSelectedFile();
                        input.setText(curFile.getPath());
                    } catch (Exception ignored) {

                    }
                }
            });

            chooser.setFileSelectionMode(selectionMode);
            chooser.showOpenDialog(null);
        };
    }

    private EventHandler<ActionEvent> getButtonClickEventHandler(Boolean dryRun) {
        return new EventHandler<>() {

            private File getPostsDirFromInput(String ignoredInput) {
                File inputFile = new File(view.dirInput.getText());

                if (inputFile.getPath().endsWith(POSTS_DIRECTORY)) {
                    return inputFile;
                }

                return new File(inputFile.getPath() + File.separator + POSTS_DIRECTORY);
            }

            @Override
            public void handle(ActionEvent t) {
                if (view.toolInput.getText().isEmpty() || view.dirInput.getText().isEmpty()) {
                    Alert alert = new Alert(Alert.AlertType.ERROR, "Both fields must be filled", ButtonType.OK);
                    alert.showAndWait();
                    return;
                }

                File exiftoolFile = new File(view.toolInput.getText());
                if (!exiftoolFile.exists()) {
                    Alert alert = new Alert(Alert.AlertType.ERROR, "Can't find exiftool file " + exiftoolFile.getPath(), ButtonType.OK);
                    alert.showAndWait();
                    return;
                }

                // If on Windows and we have been given the dir instead of exe file, add the exe to the path
                if (isWindows() && exiftoolFile.isDirectory()) {
                    exiftoolFile = new File(exiftoolFile.getPath() + File.separator + "exiftool.exe");
                }

                // If on Windows we have not been given a path to a file called exiftool.exe then complain
                // The standard download from the exiftool website gives you exiftool(-k).exe :(
                if (isWindows() && !exiftoolFile.getPath().endsWith("exiftool.exe")) {
                    Alert alert = new Alert(Alert.AlertType.ERROR, "Please rename your " + exiftoolFile.getPath() + " to exiftool.exe", ButtonType.OK);
                    alert.showAndWait();
                    return;
                }

                File dirFile = getPostsDirFromInput(view.dirInput.getText());
                if (!dirFile.exists() || !dirFile.isDirectory()) {
                    Alert alert = new Alert(Alert.AlertType.ERROR, "Directory does not exist: " + dirFile.getPath(), ButtonType.OK);
                    alert.showAndWait();
                    return;
                }

                try {
                    ObservableList<String> lines = FXCollections.observableArrayList();
                    ListView<String> listView = new ListView<>(lines);
                    listView.setEditable(false);

                    // Keep the list view scrolled to the bottom
                    lines.addListener((ListChangeListener<String>) c -> listView.scrollTo(listView.getItems().size() - 1));

                    lines.add("Task is starting...");

                    stage.setScene(new Scene(listView, 800, 500));
                    stage.show();

                    // Try to create a fancy pooled and stay open exiftool
                    // TODO exif tool creation should be done as part of the task (not in the UI thread)
                    final ExifTool finalExifTool;
                    ExifTool exifTool;
                    boolean stayOpen = true;
                    try {
                        ExifToolBuilder builder = new ExifToolBuilder();
                        builder.withPath(exiftoolFile);

                        // If we have more than one processor, use a pool strategy of that size
                        if (Runtime.getRuntime().availableProcessors() > 1) {
                            builder.withPoolSize(Runtime.getRuntime().availableProcessors());
                        }

                        builder.enableStayOpen();
                        exifTool = builder.build();
                    } catch (UnsupportedFeatureException ex) {
                        // Fallback to just a pooled tool
                        ExifToolBuilder builder = new ExifToolBuilder();
                        builder.withPath(exiftoolFile);

                        // If we have more than two processors, use a pool strategy
                        if (Runtime.getRuntime().availableProcessors() > 2) {
                            // But always leave 1 processor totally free
                            builder.withPoolSize(Runtime.getRuntime().availableProcessors() - 1);
                        }

                        stayOpen = false;
                        exifTool = builder.build();
                    }

                    finalExifTool = exifTool;

                    String initialStateMessage = "Version: " + version + "\n" +
                            "OS: " + System.getProperty("os.name") + "\n" +
                            "Exiftool: " + finalExifTool.getVersion() + "\n" +
                            "Exiftool Poolsize: " + Runtime.getRuntime().availableProcessors() + "\n" +
                            "Exiftool Stayopen: " + stayOpen + "\n" +
                            "Debug: " + view.debugCheckbox.isSelected() + "\n" +
                            "Dry run: " + dryRun + "\n" +
                            "File processing mode: " + (view.copyToProcessedRadio.isSelected() ? "Copy to Processed folder" : "Overwrite originals") + "\n" +
                            "Process posts: " + view.processPostsCheckbox.isSelected() + "\n" +
                            "Process messages: " + view.processMessagesCheckbox.isSelected() + "\n" +
                            "Process files: " + view.processFilesCheckbox.isSelected() + "\n" +
                            "-------------------------------------------------";
                    System.out.println(initialStateMessage);

                    ProcessingTask task = new ProcessingTask(
                            lines,
                            dirFile,
                            finalExifTool,
                            initialStateMessage,
                            new MainOptions(
                                    view.debugCheckbox.isSelected(),
                                    dryRun,
                                    view.overwriteOriginalsRadio.isSelected(),
                                    view.copyToProcessedRadio.isSelected(),
                                    view.processPostsCheckbox.isSelected(),
                                    view.processMessagesCheckbox.isSelected(),
                                    view.processFilesCheckbox.isSelected()
                            )
                    );

                    // Make sure if the window is closed while task is still running, everything exits
                    // There is probably a nicer way to do all of this, but this should do for now...
                    Platform.setImplicitExit(true);
                    stage.setOnCloseRequest(t1 -> {
                        // Cancel the task (will stop processing & close exiftool etc)
                        task.cancel();

                        if (!task.taskIsTidy) {
                            // Wait for a bit for the task to finish tidying up (taskIsTidy will be true when that is done)
                            // This does lock the UI for 1 second, but this was while the task was executing, so the user probably expects some delay.
                            // There is probably a cleaner way to do this....
                            try {
                                TimeUnit.SECONDS.sleep(2);
                            } catch (InterruptedException ignored) {
                            }
                        }

                        // Force the whole thing to come down..
                        Platform.exit();
                        System.exit(0);
                    });

                    // Start a single simple thread
                    Thread th = new Thread(task);
                    th.setDaemon(true);
                    th.start();

                } catch (Exception e) {
                    System.out.println(e.getMessage());
                }
            }
        };
    }

}
