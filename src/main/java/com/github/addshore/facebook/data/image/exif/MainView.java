package com.github.addshore.facebook.data.image.exif;

import javafx.fxml.FXMLLoader;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.util.Objects;

public class MainView {

    // Main view
    public final VBox dataEntryView;

    // Inputs
    public final TextField dirInput;
    public final Button dirInputBrowse;

    public final Label toolLabel;
    public final TextField toolInput;
    public final Button toolInputBrowse;

    public final ToggleGroup fileProcessingModeGroup;
    public final RadioButton overwriteOriginalsRadio;
    public final RadioButton copyToProcessedRadio;
    public final CheckBox processPostsCheckbox;
    public final CheckBox processMessagesCheckbox;
    public final CheckBox processFilesCheckbox;

    public final Label versionLabel;
    public final Hyperlink hyperLinkAddshore;
    public final Hyperlink hyperLinkCoffee;
    public final Hyperlink hyperLinkExif;

    public final Button runButton;
    public final Button dryRunButton;
    public final CheckBox debugCheckbox;

    public MainView() throws IOException {
        dataEntryView = FXMLLoader.load(Objects.requireNonNull(getClass().getResource("dataEntry.fxml")));

        // Get the input fields directly from the VBox
        dirInput = (TextField) dataEntryView.lookup("#dirInput");
        dirInputBrowse = (Button) dataEntryView.lookup("#dirInputBrowse");
        
        toolLabel = (Label) dataEntryView.lookup("#toolLabel");
        toolInput = (TextField) dataEntryView.lookup("#toolInput");
        toolInputBrowse = (Button) dataEntryView.lookup("#toolInputBrowse");
        
        // Get the radio buttons and set up the toggle group
        overwriteOriginalsRadio = (RadioButton) dataEntryView.lookup("#overwriteOriginalsRadio");
        copyToProcessedRadio = (RadioButton) dataEntryView.lookup("#copyToProcessedRadio");
        
        // Create and set up the toggle group
        fileProcessingModeGroup = new ToggleGroup();
        overwriteOriginalsRadio.setToggleGroup(fileProcessingModeGroup);
        copyToProcessedRadio.setToggleGroup(fileProcessingModeGroup);
        
        // Get the checkboxes
        processPostsCheckbox = (CheckBox) dataEntryView.lookup("#processPostsCheckbox");
        processMessagesCheckbox = (CheckBox) dataEntryView.lookup("#processMessagesCheckbox");
        processFilesCheckbox = (CheckBox) dataEntryView.lookup("#processFilesCheckbox");
        
        // Get the action buttons
        runButton = (Button) dataEntryView.lookup("#runButton");
        dryRunButton = (Button) dataEntryView.lookup("#dryRunButton");
        debugCheckbox = (CheckBox) dataEntryView.lookup("#debugCheckbox");
        
        // Get the footer elements
        versionLabel = (Label) dataEntryView.lookup("#versionLabel");
        hyperLinkAddshore = (Hyperlink) dataEntryView.lookup("#hyperLinkAddshore");
        hyperLinkCoffee = (Hyperlink) dataEntryView.lookup("#hyperLinkCoffee");
        hyperLinkExif = (Hyperlink) dataEntryView.lookup("#hyperLinkExif");
    }

}
