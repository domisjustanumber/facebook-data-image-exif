package com.github.addshore.facebook.data.image.exif;

import com.thebuzzmedia.exiftool.ExifTool;
import com.thebuzzmedia.exiftool.Format;
import com.thebuzzmedia.exiftool.Tag;
import com.thebuzzmedia.exiftool.core.StandardTag;
import javafx.application.Platform;
import javafx.concurrent.Task;
import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class ProcessingTask extends Task<Object> {

    Boolean taskIsTidy = true;
    private final List<String> outputList;
    private final File dir;
    private final ExifTool exifTool;
    private String stateMessage;
    private final MainOptions mainOptions;
    
    // Statistics tracking
    private int statProcessedImages = 0;
    private int statFailedImages = 0;
    private int statCopiedAlbums = 0;

    ProcessingTask(List<String> outputList, File dir, ExifTool exifTool, String initialStateMessage, MainOptions mainOptions) {
        this.outputList = outputList;
        this.dir = dir;
        this.exifTool = exifTool;
        this.stateMessage = initialStateMessage;
        this.mainOptions = mainOptions;
    }

    private void appendMessage(String string) {
        System.out.println("ProcessingTask: " + string);
        // Do the update on the UI thread
        Platform.runLater(() -> outputList.add(string));
        stateMessage = stateMessage + "\n" + string;
    }

    private void appendDebugMessage(String string) {
        string = "debug: " + string;
        if (this.mainOptions.isDebugMode()) {
            this.appendMessage(string);
        } else {
            System.out.println("ProcessingTask: " + string);
        }
    }

    @Override
    protected Object call() {
        // Task is starting, so no longer tidy
        taskIsTidy = false;

        try {
            processTask();
        } catch (JSONException | IOException exception) {
            appendMessage("Something went wrong while running the task.");
            appendMessage("ERROR: " + exception.getMessage());
            appendMessage("Task may not have completely finished.");
        }

        try {
            exifTool.close();
        } catch (Exception e) {
            System.out.println(e.getMessage());
            appendDebugMessage("There was a problem closing exiftool");
            appendDebugMessage(e.getMessage());
        }

        // We have cleaned up, so the task is tidy again...
        taskIsTidy = true;

        return null;
    }

    private void processTask() throws IOException, JSONException {
        // Process posts if enabled
        if (this.mainOptions.shouldProcessPosts()) {
            processPosts();
        } else {
            appendMessage("Skipping posts processing (disabled)");
        }

        // Process messages if enabled
        if (this.mainOptions.shouldProcessMessages()) {
            appendMessage("Looking for messages...");
            if (this.mainOptions.shouldProcessFiles()) {
                appendMessage("File processing: enabled (will process photos, videos, and files)");
            } else {
                appendMessage("File processing: disabled (will only process photos and videos)");
            }
            processMessages();
        } else {
            appendMessage("Skipping messages processing (disabled)");
        }

        if (this.isCancelled()) {
            appendMessage("Task cancelled, run not complete");
        } else {
            appendMessage("-------------------------------------------------");
            appendMessage("Task complete");
            if (statProcessedImages != 0) {
                appendMessage("Images processed: " + statProcessedImages);
            }
            if (statFailedImages != 0) {
                appendMessage("Images failed: " + statFailedImages);
            }
            if (this.mainOptions.shouldCopyToNewFolders() && statCopiedAlbums != 0) {
                appendMessage("Albums copied to Processed folder: " + statCopiedAlbums);
            }
            if (statFailedImages != 0) {
                appendMessage("See the full output for detailed failure reasons...");
            }
        }
    }

    private void processPosts() throws IOException, JSONException {
        // Find all album json files
        appendMessage("Looking for albums...");
        File albumDir = new File(dir.toPath() + File.separator + "album");
        appendDebugMessage("In album dir: " + albumDir.getPath());

        File[] albumJsonFiles = albumDir.listFiles((dir, filename) -> filename.endsWith(".json"));
        File[] albumHtmlFiles = albumDir.listFiles((dir, filename) -> filename.endsWith(".html"));

        appendMessage(Objects.requireNonNull(albumJsonFiles).length + " JSON album files found");
        appendMessage(Objects.requireNonNull(albumHtmlFiles).length + " HTML album files found");

        // Stop if we detected no JSON but did find HTML
        if (albumJsonFiles.length == 0 && albumHtmlFiles.length != 0) {
            appendMessage("This program currently only works with the JSON facebook downloads");
            return;
        }

        // Process the album
        for (File albumJsonFile : albumJsonFiles) {
            appendDebugMessage("Loading album file " + albumJsonFile.getPath());
            InputStream inputStream = new FileInputStream(albumJsonFile);

            appendDebugMessage("Writing to internal string");
            StringWriter writer = new StringWriter();
            IOUtils.copy(inputStream, writer, "UTF-8");
            String jsonTxt = writer.toString();
            appendDebugMessage("Loading album object");
            JSONObject albumJson = new JSONObject(jsonTxt);
            if (!albumJson.has("photos")) {
                appendDebugMessage("Album has no photos");
                continue;
            }

            appendDebugMessage("Getting album photos, JSON = " + albumJson);
            JSONArray albumPhotos = albumJson.getJSONArray("photos");

            String albumName = albumJson.getString("name");
            appendMessage("Album: " + albumName + ", " + albumPhotos.length() + " photos");

            // Process the photos in the album first
            for (int i = 0; i < albumPhotos.length(); i++) {
                appendDebugMessage("Getting photo data: " + i);
                JSONObject photoData = albumPhotos.getJSONObject(i);

                appendMessage(" - Processing " + photoData.getString("uri"));
                try {
                    if (processFile(photoData)) {
                        statProcessedImages++;
                    } else {
                        statFailedImages++;
                    }
                } catch (JSONException jsonException) {
                    statFailedImages++;
                    appendMessage("Something went wrong while getting data for the image.");
                    appendMessage("ERROR: " + jsonException.getMessage());
                    appendMessage("Image has not been processed entirely");
                } catch (IOException ioException) {
                    statFailedImages++;
                    appendMessage("Something went wrong while writing data to the image.");
                    appendMessage("ERROR: " + ioException.getMessage());
                    appendMessage("Image has not been processed entirely");
                }

                // If the task has been cancelled, then stop processing images
                if (this.isCancelled()) {
                    // TODO some sort of cancelled exception instead?
                    break;
                }
            }

            // Copy album photos to Processed directory with proper folder names (after processing all photos)
            if (this.mainOptions.shouldCopyToNewFolders() && albumName != null && !albumName.trim().isEmpty()) {
                try {
                    if (copyAlbumToProcessedFolder(albumJsonFile, albumName, albumPhotos)) {
                        statCopiedAlbums++;
                    }
                } catch (Exception e) {
                    appendMessage("ERROR: Failed to copy album to processed folder: " + e.getMessage());
                }
            }

            // Hint to garbage collect after each album?
            System.gc();

            // If the task has been cancelled, then stop processing albums
            if (this.isCancelled()) {
                // TODO some sort of cancelled exception instead?
                break;
            }
        }
    }

    private void processMessages() throws IOException, JSONException {
        // Look for messages in inbox and e2ee_cutover directories
        File messagesDir = new File(dir.getParentFile().toPath() + File.separator + "messages");
        if (!messagesDir.exists()) {
            appendDebugMessage("Messages directory not found: " + messagesDir.getPath());
            return;
        }

        // Process inbox messages
        File inboxDir = new File(messagesDir.toPath() + File.separator + "inbox");
        if (inboxDir.exists()) {
            appendMessage("Processing inbox messages...");
            processMessageDirectory(inboxDir);
        }

        // Process e2ee_cutover messages
        File e2eeDir = new File(messagesDir.toPath() + File.separator + "e2ee_cutover");
        if (e2eeDir.exists()) {
            appendMessage("Processing e2ee_cutover messages...");
            processMessageDirectory(e2eeDir);
        }
    }

    private void processMessageDirectory(File messageDir) throws IOException, JSONException {
        File[] conversationDirs = messageDir.listFiles(File::isDirectory);
        if (conversationDirs == null) {
            appendDebugMessage("No conversation directories found in: " + messageDir.getPath());
            return;
        }

        int totalPhotosProcessed = 0;
        int totalVideosProcessed = 0;
        int totalFilesProcessed = 0;
        int totalFailed = 0;
        int totalConversationsCopied = 0;

        for (File conversationDir : conversationDirs) {
            appendDebugMessage("Processing conversation: " + conversationDir.getName());
            
            // Look for JSON files in the conversation directory
            File[] jsonFiles = conversationDir.listFiles((dir, filename) -> filename.endsWith(".json"));
            if (jsonFiles == null || jsonFiles.length == 0) {
                appendDebugMessage("No JSON files found in conversation: " + conversationDir.getName());
                continue;
            }

            // Collect all media files from this conversation for copying
            List<JSONObject> conversationMedia = new ArrayList<>();
            String conversationName = null;
            String conversationTitle = null;

            for (File jsonFile : jsonFiles) {
                try {
                    appendDebugMessage("Loading message file: " + jsonFile.getPath());
                    InputStream inputStream = new FileInputStream(jsonFile);
                    
                    StringWriter writer = new StringWriter();
                    IOUtils.copy(inputStream, writer, "UTF-8");
                    String jsonTxt = writer.toString();
                    
                    JSONObject messageJson = new JSONObject(jsonTxt);
                    if (!messageJson.has("messages")) {
                        appendDebugMessage("Message file has no messages array: " + jsonFile.getName());
                        continue;
                    }

                    // Get conversation title if not already set
                    if (conversationTitle == null && messageJson.has("title")) {
                        conversationTitle = messageJson.getString("title");
                        appendDebugMessage("Found conversation title: " + conversationTitle);
                    }

                    // Get conversation name from participants if not already set (fallback)
                    if (conversationName == null && messageJson.has("participants")) {
                        conversationName = buildConversationName(messageJson.getJSONArray("participants"));
                    }

                    JSONArray messages = messageJson.getJSONArray("messages");
                    appendDebugMessage("Processing " + messages.length() + " messages in " + jsonFile.getName());

                    // Process each message for photos and videos
                    for (int i = 0; i < messages.length(); i++) {
                        JSONObject message = messages.getJSONObject(i);
                        
                        // Check for photos
                        if (message.has("photos")) {
                            JSONArray photos = message.getJSONArray("photos");
                            for (int j = 0; j < photos.length(); j++) {
                                JSONObject photoData = photos.getJSONObject(j);
                                try {
                                    if (processFile(photoData)) {
                                        totalPhotosProcessed++;
                                        conversationMedia.add(photoData);
                                    } else {
                                        totalFailed++;
                                    }
                                } catch (Exception e) {
                                    totalFailed++;
                                    appendMessage("ERROR: Failed to process photo in message: " + e.getMessage());
                                }
                            }
                        }

                        // Check for videos
                        if (message.has("videos")) {
                            JSONArray videos = message.getJSONArray("videos");
                            for (int j = 0; j < videos.length(); j++) {
                                JSONObject videoData = videos.getJSONObject(j);
                                try {
                                    if (processFile(videoData)) {
                                        totalVideosProcessed++;
                                        conversationMedia.add(videoData);
                                    } else {
                                        totalFailed++;
                                    }
                                } catch (Exception e) {
                                    totalFailed++;
                                    appendMessage("ERROR: Failed to process video in message: " + e.getMessage());
                                }
                            }
                        }

                        // Check for files (all files with creation_timestamp) - only if enabled
                        if (this.mainOptions.shouldProcessFiles() && message.has("files")) {
                            JSONArray files = message.getJSONArray("files");
                            for (int j = 0; j < files.length(); j++) {
                                JSONObject fileData = files.getJSONObject(j);
                                // Process all files that have creation_timestamp
                                if (fileData.has("uri") && fileData.has("creation_timestamp")) {
                                    try {
                                        if (processFile(fileData)) {
                                            // Categorize based on file extension
                                            if (isVideoFile(fileData.getString("uri"))) {
                                                totalVideosProcessed++;
                                            } else {
                                                totalFilesProcessed++;
                                            }
                                            conversationMedia.add(fileData);
                                        } else {
                                            totalFailed++;
                                        }
                                    } catch (Exception e) {
                                        totalFailed++;
                                        appendMessage("ERROR: Failed to process file in message: " + e.getMessage());
                                    }
                                }
                            }
                        }

                        // If the task has been cancelled, then stop processing messages
                        if (this.isCancelled()) {
                            break;
                        }
                    }

                } catch (Exception e) {
                    appendMessage("ERROR: Failed to process message file " + jsonFile.getName() + ": " + e.getMessage());
                }
            }

            // Copy conversation media to Processed/Messages directory
            if (this.mainOptions.shouldCopyToNewFolders() && !conversationMedia.isEmpty()) {
                try {
                    // Use conversation title if available, otherwise fall back to participant names, then directory name
                    String finalConversationName;
                    if (conversationTitle != null && !conversationTitle.trim().isEmpty()) {
                        finalConversationName = conversationTitle;
                        appendDebugMessage("Using conversation title as folder name: " + conversationTitle);
                    } else if (conversationName != null) {
                        finalConversationName = conversationName;
                        appendDebugMessage("Using participant names as folder name: " + conversationName);
                    } else {
                        finalConversationName = conversationDir.getName();
                        appendDebugMessage("Using directory name as folder name: " + conversationDir.getName());
                    }
                    
                    if (copyConversationToProcessedFolder(conversationDir, finalConversationName, conversationMedia)) {
                        totalConversationsCopied++;
                    }
                } catch (Exception e) {
                    appendMessage("ERROR: Failed to copy conversation to processed folder: " + e.getMessage());
                }
            }

            // If the task has been cancelled, then stop processing conversations
            if (this.isCancelled()) {
                break;
            }
        }

        appendMessage("Message processing complete:");
        appendMessage("Photos processed: " + totalPhotosProcessed);
        appendMessage("Videos processed: " + totalVideosProcessed);
        if (this.mainOptions.shouldProcessFiles()) {
            appendMessage("Files processed: " + totalFilesProcessed);
        } else {
            appendMessage("Files processing: disabled");
        }
        appendMessage("Failed: " + totalFailed);
        if (this.mainOptions.shouldCopyToNewFolders()) {
            appendMessage("Conversations copied to Processed/Messages folder: " + totalConversationsCopied);
        }
    }

    private boolean isVideoFile(String uri) {
        if (uri == null) return false;
        String lowerUri = uri.toLowerCase();
        return lowerUri.endsWith(".mp4") || lowerUri.endsWith(".avi") || lowerUri.endsWith(".mov") || 
               lowerUri.endsWith(".wmv") || lowerUri.endsWith(".flv") || lowerUri.endsWith(".webm") ||
               lowerUri.endsWith(".mkv") || lowerUri.endsWith(".m4v") || lowerUri.endsWith(".3gp");
    }

    /**
     * Builds a conversation name from the participants array
     * @param participants The participants array from the message JSON
     * @return A sanitized conversation name
     */
    private String buildConversationName(JSONArray participants) {
        if (participants == null || participants.length() == 0) {
            return "unnamed_conversation";
        }

        StringBuilder nameBuilder = new StringBuilder();
        for (int i = 0; i < participants.length(); i++) {
            try {
                JSONObject participant = participants.getJSONObject(i);
                if (participant.has("name")) {
                    if (nameBuilder.length() > 0) {
                        nameBuilder.append("_");
                    }
                    nameBuilder.append(participant.getString("name"));
                }
            } catch (JSONException e) {
                appendDebugMessage("Warning: Could not parse participant " + i + ": " + e.getMessage());
            }
        }

        String conversationName = nameBuilder.toString();
        if (conversationName.isEmpty()) {
            return "unnamed_conversation";
        }

        return sanitizeFolderName(conversationName);
    }

    /**
     * Copies conversation media to a new "Processed/Messages" directory with properly named folders
     * @param conversationDir The conversation directory
     * @param conversationName The name to use for the new folder
     * @param conversationMedia The list of media objects from the conversation
     * @return true if files were copied successfully, false otherwise
     */
    private Boolean copyConversationToProcessedFolder(File conversationDir, String conversationName, List<JSONObject> conversationMedia) throws JSONException, IOException {
        if (conversationMedia.isEmpty()) {
            appendDebugMessage("No media in conversation, skipping copy");
            return false;
        }

        // Sanitize the conversation name for use as a folder name
        String sanitizedConversationName = sanitizeFolderName(conversationName);
        appendDebugMessage("Sanitized conversation name: " + sanitizedConversationName);
        
        // Create the Processed/Messages directory structure
        File processedDir = new File(dir.getParentFile().getParentFile().toPath() + File.separator + "Processed");
        File messagesDir = new File(processedDir.toPath() + File.separator + "Messages");
        File conversationDirDest = new File(messagesDir.toPath() + File.separator + sanitizedConversationName);
        
        appendDebugMessage("Processed directory: " + processedDir.getPath());
        appendDebugMessage("Messages directory: " + messagesDir.getPath());
        appendDebugMessage("Conversation directory: " + conversationDirDest.getPath());
        
        // Create directories if they don't exist
        if (!this.mainOptions.isDryMode()) {
            if (!processedDir.exists() && !processedDir.mkdirs()) {
                appendMessage("ERROR: Failed to create Processed directory: " + processedDir.getPath());
                return false;
            }
            if (!messagesDir.exists() && !messagesDir.mkdirs()) {
                appendMessage("ERROR: Failed to create Messages directory: " + messagesDir.getPath());
                return false;
            }
            if (!conversationDirDest.exists() && !conversationDirDest.mkdirs()) {
                appendMessage("ERROR: Failed to create conversation directory: " + conversationDirDest.getPath());
                return false;
            }
        } else {
            appendDebugMessage("DRY RUN: Would create directories: " + processedDir.getPath() + ", " + messagesDir.getPath() + ", and " + conversationDirDest.getPath());
        }
        
        int copiedFiles = 0;
        int failedFiles = 0;
        
        // Copy each media file
        for (JSONObject mediaData : conversationMedia) {
            String mediaUri = mediaData.getString("uri");
            
            // Get the source file path
            File sourceFile = new File(dir.getParentFile().toPath() + File.separator + mediaUri.replace("your_facebook_activity/", ""));
            
            // Get just the filename from the URI
            String[] uriParts = mediaUri.split("/");
            String fileName = uriParts[uriParts.length - 1];
            
            // Create the destination file path
            File destFile = new File(conversationDirDest.toPath() + File.separator + fileName);
            
            appendDebugMessage("Copying: " + sourceFile.getPath() + " -> " + destFile.getPath());
            
            if (!sourceFile.exists()) {
                appendMessage("ERROR: Source file does not exist: " + sourceFile.getPath());
                failedFiles++;
                continue;
            }
            
            if (!this.mainOptions.isDryMode()) {
                try {
                    // Copy the file while preserving metadata
                    copyFileWithMetadata(sourceFile, destFile);
                    
                    // Process the copied file with EXIF data
                    if (processCopiedFile(destFile, mediaData)) {
                        copiedFiles++;
                        appendDebugMessage("Successfully copied and processed: " + fileName);
                    } else {
                        failedFiles++;
                        appendMessage("ERROR: Failed to process copied file " + fileName);
                    }
                } catch (IOException e) {
                    appendMessage("ERROR: Failed to copy file " + fileName + ": " + e.getMessage());
                    failedFiles++;
                }
            } else {
                appendDebugMessage("DRY RUN: Would copy and process " + fileName);
                copiedFiles++;
            }
        }
        
        if (copiedFiles > 0) {
            appendMessage("Copied " + copiedFiles + " files to '" + sanitizedConversationName + "' conversation folder");
            if (failedFiles > 0) {
                appendMessage("Failed to copy " + failedFiles + " files");
            }
            return true;
        } else {
            appendMessage("ERROR: No files were copied successfully");
            return false;
        }
    }

    private Boolean processFile(JSONObject photoData) throws JSONException, IOException {
        File imageFile = new File(dir.getParentFile().toPath() + File.separator + photoData.getString("uri").replace("your_facebook_activity/", ""));
        appendDebugMessage("Image file path: " + imageFile.getPath());

        if (!imageFile.exists()) {
            appendMessage("ERROR: the file does not exist in the expected location. Is your download complete?");
            return false;
        }
        
        // If we're copying to new folders, we don't need to check if the original is writable
        // since we won't be modifying it
        if (!this.mainOptions.shouldCopyToNewFolders() && !imageFile.canWrite()) {
            appendMessage("ERROR: the file is not writable.");
            return false;
        }

        JSONObject photoMetaData = null;

        // First look for the actual meta data for the media file that was uploaded
        if (photoData.has("media_metadata")) {
            JSONObject mediaMetaData = photoData.getJSONObject("media_metadata");
            if (mediaMetaData.has("photo_metadata")) {
                photoMetaData = mediaMetaData.getJSONObject("photo_metadata");
            } else {
                appendDebugMessage("WARNING: Got media_metadata but no photo_metadata, FAILING for image...");
            }
        }
        // Otherwise use the higher level data, which isn't data about the photo itself, but rather about the photo upload to facebook
        // which won't have things like iso... but will have the creation_timestamp
        if (photoMetaData == null && photoData.has("creation_timestamp")) {
            // If this high level element has the creation_timestamp then assume it as the photo meta data?
            photoMetaData = photoData;
            appendDebugMessage("Falling back to root meta data for image");
        }
        // Otherwise we couldn't find anything at all :( so skip the file...
        if (photoMetaData == null) {
            appendDebugMessage("WARNING: No media_metadata found, and no fallback used, FAILING for image...");
            appendMessage("Skipping image (due to no meta data found)");
            return false;
        }

        // Figure out the time the picture was taken
        String takenTimestamp = null;
        if (photoMetaData.has("taken_timestamp")) {
            // Keep timestamp as is
            takenTimestamp = photoMetaData.getString("taken_timestamp");
            appendDebugMessage(StandardTag.DATE_TIME_ORIGINAL + " got from taken_timestamp of media file:" + takenTimestamp);
        } else if (photoMetaData.has("modified_timestamp")) {
            // It's missing, replace with modified
            takenTimestamp = photoMetaData.getString("modified_timestamp");
            appendDebugMessage(StandardTag.DATE_TIME_ORIGINAL + " got from modified_timestamp of media file:" + takenTimestamp);
        } else if (photoMetaData.has("creation_timestamp")) {
            // Fallback to the creation timestamp
            takenTimestamp = photoMetaData.getInt("creation_timestamp") + "";
            appendDebugMessage(StandardTag.DATE_TIME_ORIGINAL + " got from creation_timestamp of media file:" + takenTimestamp);
        } else if (photoData.has("creation_timestamp")) {
            // Fallback to the facebook upload creation timestamp, rather than one from the media file itself..
            takenTimestamp = photoData.getInt("creation_timestamp") + "";
            appendDebugMessage(StandardTag.DATE_TIME_ORIGINAL + " got from creation_timestamp of facebook upload:" + takenTimestamp);
        } else {
            appendDebugMessage(StandardTag.DATE_TIME_ORIGINAL + " could not find a source");
        }
        if (takenTimestamp != null) {
            takenTimestamp = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss").format(new Date(Long.parseLong(takenTimestamp) * 1000));
        }

        // And set a modified timestamp
        String modifiedTimestamp;
        if (photoMetaData.has("modified_timestamp")) {
            modifiedTimestamp = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss").format(new Date(Long.parseLong(photoMetaData.getString("modified_timestamp")) * 1000));
            appendDebugMessage(CustomTag.MODIFYDATE + " got from modified_timestamp:" + photoMetaData.getString("modified_timestamp"));
        } else {
            modifiedTimestamp = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss").format(new Date());
            appendDebugMessage(CustomTag.MODIFYDATE + " could not find a source, using today");
        }

        // fstop
        String fStop = null;
        if (photoMetaData.has("f_stop")) {
            String[] parts = photoMetaData.getString("f_stop").split("/");
            if (parts.length > 1) {
                fStop = Double.toString(Double.parseDouble(parts[0]) / Double.parseDouble(parts[1]));
            } else {
                fStop = photoMetaData.getString("f_stop");
            }
            appendDebugMessage(CustomTag.FNUMBER + " got data " + fStop);
        } else {
            appendDebugMessage(CustomTag.FNUMBER + " could not find data");
        }

        appendDebugMessage("Constructing exif data object");
        Map<Tag, String> exifData = new HashMap<>();

        exifData.put(CustomTag.MODIFYDATE, modifiedTimestamp);

        if (takenTimestamp != null) {
            exifData.put(StandardTag.DATE_TIME_ORIGINAL, takenTimestamp);
        }

        if (photoMetaData.has("camera_make")) {
            exifData.put(StandardTag.MAKE, photoMetaData.getString("camera_make"));
            appendDebugMessage(StandardTag.MAKE + " got data " + photoMetaData.getString("camera_make"));
        } else {
            appendDebugMessage(StandardTag.MAKE + " could not find data");
        }
        if (photoMetaData.has("camera_model")) {
            exifData.put(StandardTag.MODEL, photoMetaData.getString("camera_model"));
            appendDebugMessage(StandardTag.MODEL + " got data " + photoMetaData.getString("camera_model"));
        } else {
            appendDebugMessage(StandardTag.MODEL + " could not find data");
        }

        if (photoMetaData.has("latitude") && photoMetaData.has("longitude")) {
            exifData.put(StandardTag.GPS_LATITUDE, photoMetaData.getString("latitude"));
            exifData.put(StandardTag.GPS_LATITUDE_REF, photoMetaData.getString("latitude"));
            exifData.put(StandardTag.GPS_LONGITUDE, photoMetaData.getString("longitude"));
            exifData.put(StandardTag.GPS_LONGITUDE_REF, photoMetaData.getString("longitude"));
            exifData.put(StandardTag.GPS_ALTITUDE, "0");
            exifData.put(StandardTag.GPS_ALTITUDE_REF, "0");
            appendDebugMessage(StandardTag.GPS_LATITUDE + " got data " + photoMetaData.getString("latitude"));
            appendDebugMessage(StandardTag.GPS_LONGITUDE + " got data " + photoMetaData.getString("longitude"));
        } else {
            appendDebugMessage("COORDINATES could not find data");
        }

        if (photoMetaData.has("exposure")) {
            exifData.put(CustomTag.EXPOSURE, photoMetaData.getString("exposure"));
            appendDebugMessage(CustomTag.EXPOSURE + " got data " + photoMetaData.getString("exposure"));
        } else {
            appendDebugMessage(CustomTag.EXPOSURE + " could not find data");
        }
        if (photoMetaData.has("iso_speed")) {
            exifData.put(StandardTag.ISO, photoMetaData.getString("iso_speed"));
            appendDebugMessage(StandardTag.ISO + " got data " + photoMetaData.getString("iso_speed"));
        } else {
            appendDebugMessage(StandardTag.ISO + " could not find data");
        }
        if (photoMetaData.has("focal_length")) {
            exifData.put(StandardTag.FOCAL_LENGTH, photoMetaData.getString("focal_length"));
            appendDebugMessage(StandardTag.FOCAL_LENGTH + " got data " + photoMetaData.getString("focal_length"));
        } else {
            appendDebugMessage(StandardTag.FOCAL_LENGTH + " could not find data");
        }
        if (fStop != null) {
            exifData.put(CustomTag.FNUMBER, fStop);
        }

        // This can be used to add more args to the execution of exiftool
        Format format = CustomFormat.DEFAULT;
        if (mainOptions.shouldOverwriteOriginals()) {
            format = CustomFormat.DEFAULT_OVERWRITE_ORIGINAL;
        }

        // If we're copying to new folders, don't modify the original files
        // The EXIF data will be written to the copied files instead
        if (!this.mainOptions.isDryMode() && !this.mainOptions.shouldCopyToNewFolders()) {
            appendDebugMessage("calling setImageMeta for " + photoData.getString("uri"));
            exifTool.setImageMeta(imageFile, format, exifData);
            
            // Set file creation and modified dates based on takenTimestamp
            if (takenTimestamp != null) {
                try {
                    // Parse the takenTimestamp back to a Date object
                    SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss");
                    Date takenDate = dateFormat.parse(takenTimestamp);
                    long takenTime = takenDate.getTime();
                    
                    // Set both creation and modified times to the taken timestamp
                    if (imageFile.setLastModified(takenTime)) {
                        appendDebugMessage("Successfully set file modification time to taken timestamp: " + takenTimestamp);
                    } else {
                        appendDebugMessage("Warning: Could not set file modification time");
                    }
                    
                    // Try to set creation time using NIO (works on some systems)
                    try {
                        java.nio.file.Files.setAttribute(
                            imageFile.toPath(), 
                            "basic:creationTime", 
                            java.nio.file.attribute.FileTime.fromMillis(takenTime)
                        );
                        appendDebugMessage("Successfully set file creation time to taken timestamp: " + takenTimestamp);
                    } catch (Exception e) {
                        appendDebugMessage("Warning: Could not set file creation time (this is normal on some systems): " + e.getMessage());
                    }
                    
                } catch (Exception e) {
                    appendDebugMessage("Warning: Could not parse taken timestamp for file timestamps: " + e.getMessage());
                }
            }
        } else if (this.mainOptions.isDryMode()) {
            appendDebugMessage("skipping setImageMeta for " + photoData.getString("uri") + " (dryrun)");
            if (takenTimestamp != null) {
                appendDebugMessage("DRY RUN: Would set file timestamps to taken timestamp: " + takenTimestamp);
            }
        } else if (this.mainOptions.shouldCopyToNewFolders()) {
            appendDebugMessage("skipping setImageMeta for " + photoData.getString("uri") + " (will process copied files instead)");
            if (takenTimestamp != null) {
                appendDebugMessage("Will set file timestamps on copied files to taken timestamp: " + takenTimestamp);
            }
        }

        return true;
    }

    /**
     * Copies album photos to a new "Processed" directory with properly named folders
     * @param albumJsonFile The JSON file containing album information
     * @param albumName The name to use for the new folder
     * @param albumPhotos The photos array from the JSON
     * @return true if files were copied successfully, false otherwise
     */
    private Boolean copyAlbumToProcessedFolder(File albumJsonFile, String albumName, JSONArray albumPhotos) throws JSONException, IOException {
        if (albumPhotos.length() == 0) {
            appendDebugMessage("No photos in album, skipping copy");
            return false;
        }

        // Sanitize the album name for use as a folder name
        String sanitizedAlbumName = sanitizeFolderName(albumName);
        appendDebugMessage("Sanitized album name: " + sanitizedAlbumName);
        
        // Create the Processed directory structure
        File processedDir = new File(dir.getParentFile().getParentFile().toPath() + File.separator + "Processed");
        File albumDir = new File(processedDir.toPath() + File.separator + sanitizedAlbumName);
        
        appendDebugMessage("Processed directory: " + processedDir.getPath());
        appendDebugMessage("Album directory: " + albumDir.getPath());
        
        // Create directories if they don't exist
        if (!this.mainOptions.isDryMode()) {
            if (!processedDir.exists() && !processedDir.mkdirs()) {
                appendMessage("ERROR: Failed to create Processed directory: " + processedDir.getPath());
                return false;
            }
            if (!albumDir.exists() && !albumDir.mkdirs()) {
                appendMessage("ERROR: Failed to create album directory: " + albumDir.getPath());
                return false;
            }
        } else {
            appendDebugMessage("DRY RUN: Would create directories: " + processedDir.getPath() + " and " + albumDir.getPath());
        }
        
        int copiedFiles = 0;
        int failedFiles = 0;
        
        // Copy each photo file
        for (int i = 0; i < albumPhotos.length(); i++) {
            JSONObject photoData = albumPhotos.getJSONObject(i);
            String photoUri = photoData.getString("uri");
            
            // Get the source file path
            File sourceFile = new File(dir.getParentFile().toPath() + File.separator + photoUri.replace("your_facebook_activity/", ""));
            
            // Get just the filename from the URI
            String[] uriParts = photoUri.split("/");
            String fileName = uriParts[uriParts.length - 1];
            
            // Create the destination file path
            File destFile = new File(albumDir.toPath() + File.separator + fileName);
            
            appendDebugMessage("Copying: " + sourceFile.getPath() + " -> " + destFile.getPath());
            
            if (!sourceFile.exists()) {
                appendMessage("ERROR: Source file does not exist: " + sourceFile.getPath());
                failedFiles++;
                continue;
            }
            
            if (!this.mainOptions.isDryMode()) {
                try {
                    // Copy the file while preserving metadata
                    copyFileWithMetadata(sourceFile, destFile);
                    
                    // Process the copied file with EXIF data
                    if (processCopiedFile(destFile, photoData)) {
                        copiedFiles++;
                        appendDebugMessage("Successfully copied and processed: " + fileName);
                    } else {
                        failedFiles++;
                        appendMessage("ERROR: Failed to process copied file " + fileName);
                    }
                } catch (IOException e) {
                    appendMessage("ERROR: Failed to copy file " + fileName + ": " + e.getMessage());
                    failedFiles++;
                }
            } else {
                appendDebugMessage("DRY RUN: Would copy and process " + fileName);
                copiedFiles++;
            }
        }
        
        if (copiedFiles > 0) {
            appendMessage("Copied " + copiedFiles + " files to '" + sanitizedAlbumName + "' folder");
            if (failedFiles > 0) {
                appendMessage("Failed to copy " + failedFiles + " files");
            }
            return true;
        } else {
            appendMessage("ERROR: No files were copied successfully");
            return false;
        }
    }

    /**
     * Sanitizes a string to be used as a folder name by removing/replacing invalid characters
     * while preserving non-English characters and Unicode support
     * @param name The original name
     * @return A sanitized folder name
     */
    private String sanitizeFolderName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "unnamed_album";
        }
        
        // Normalize Unicode characters (combines characters and their modifiers)
        String sanitized = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFC);
        
        // Replace Windows-specific invalid characters with underscores
        // Note: We preserve most Unicode characters including non-English scripts
        sanitized = sanitized.replaceAll("[<>:\"/\\|?*\u0000-\u001F\u007F-\u009F]", "_");
        
        // Remove quotation marks
        sanitized = sanitized.replaceAll("[\"']", "");
        
        // Remove leading/trailing spaces, dots, and other problematic characters
        sanitized = sanitized.trim();
        sanitized = sanitized.replaceAll("^[\\s\\.]+|[\\s\\.]+$", "");
        
        // Remove control characters and other problematic Unicode ranges
        sanitized = sanitized.replaceAll("[\\p{Cntrl}\\p{Space}]+", " ");
        
        // Replace multiple consecutive underscores with a single one
        sanitized = sanitized.replaceAll("_+", "_");
        
        // Remove leading/trailing underscores
        sanitized = sanitized.replaceAll("^_+|_+$", "");
        
        // Limit length to avoid filesystem issues (keeping it reasonable for most filesystems)
        if (sanitized.length() > 200) {
            sanitized = sanitized.substring(0, 200);
            // Ensure we don't cut in the middle of a Unicode character
            while (sanitized.length() > 0 && !Character.isHighSurrogate(sanitized.charAt(sanitized.length() - 1))) {
                sanitized = sanitized.substring(0, sanitized.length() - 1);
            }
        }
        
        // Ensure it's not empty after sanitization
        if (sanitized.trim().isEmpty()) {
            return "unnamed_album";
        }
        
        return sanitized;
    }

    /**
     * Processes a copied file with EXIF data and timestamps
     * @param destFile The destination file to process
     * @param photoData The photo data containing metadata
     * @return true if processing was successful, false otherwise
     */
    private Boolean processCopiedFile(File destFile, JSONObject photoData) throws JSONException, IOException {
        JSONObject photoMetaData = null;

        // First look for the actual meta data for the media file that was uploaded
        if (photoData.has("media_metadata")) {
            JSONObject mediaMetaData = photoData.getJSONObject("media_metadata");
            if (mediaMetaData.has("photo_metadata")) {
                photoMetaData = mediaMetaData.getJSONObject("photo_metadata");
            } else {
                appendDebugMessage("WARNING: Got media_metadata but no photo_metadata, FAILING for image...");
            }
        }
        // Otherwise use the higher level data, which isn't data about the photo itself, but rather about the photo upload to facebook
        // which won't have things like iso... but will have the creation_timestamp
        if (photoMetaData == null && photoData.has("creation_timestamp")) {
            // If this high level element has the creation_timestamp then assume it as the photo meta data?
            photoMetaData = photoData;
            appendDebugMessage("Falling back to root meta data for image");
        }
        // Otherwise we couldn't find anything at all :( so skip the file...
        if (photoMetaData == null) {
            appendDebugMessage("WARNING: No media_metadata found, and no fallback used, FAILING for image...");
            appendMessage("Skipping image (due to no meta data found)");
            return false;
        }

        // Figure out the time the picture was taken
        String takenTimestamp = null;
        if (photoMetaData.has("taken_timestamp")) {
            // Keep timestamp as is
            takenTimestamp = photoMetaData.getString("taken_timestamp");
            appendDebugMessage(StandardTag.DATE_TIME_ORIGINAL + " got from taken_timestamp of media file:" + takenTimestamp);
        } else if (photoMetaData.has("modified_timestamp")) {
            // It's missing, replace with modified
            takenTimestamp = photoMetaData.getString("modified_timestamp");
            appendDebugMessage(StandardTag.DATE_TIME_ORIGINAL + " got from modified_timestamp of media file:" + takenTimestamp);
        } else if (photoMetaData.has("creation_timestamp")) {
            // Fallback to the creation timestamp
            takenTimestamp = photoMetaData.getInt("creation_timestamp") + "";
            appendDebugMessage(StandardTag.DATE_TIME_ORIGINAL + " got from creation_timestamp of media file:" + takenTimestamp);
        } else if (photoData.has("creation_timestamp")) {
            // Fallback to the facebook upload creation timestamp, rather than one from the media file itself..
            takenTimestamp = photoData.getInt("creation_timestamp") + "";
            appendDebugMessage(StandardTag.DATE_TIME_ORIGINAL + " got from creation_timestamp of facebook upload:" + takenTimestamp);
        } else {
            appendDebugMessage(StandardTag.DATE_TIME_ORIGINAL + " could not find a source");
        }
        if (takenTimestamp != null) {
            takenTimestamp = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss").format(new Date(Long.parseLong(takenTimestamp) * 1000));
        }

        // And set a modified timestamp
        String modifiedTimestamp;
        if (photoMetaData.has("modified_timestamp")) {
            modifiedTimestamp = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss").format(new Date(Long.parseLong(photoMetaData.getString("modified_timestamp")) * 1000));
            appendDebugMessage(CustomTag.MODIFYDATE + " got from modified_timestamp:" + photoMetaData.getString("modified_timestamp"));
        } else {
            modifiedTimestamp = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss").format(new Date());
            appendDebugMessage(CustomTag.MODIFYDATE + " could not find a source, using today");
        }

        // fstop
        String fStop = null;
        if (photoMetaData.has("f_stop")) {
            String[] parts = photoMetaData.getString("f_stop").split("/");
            if (parts.length > 1) {
                fStop = Double.toString(Double.parseDouble(parts[0]) / Double.parseDouble(parts[1]));
            } else {
                fStop = photoMetaData.getString("f_stop");
            }
            appendDebugMessage(CustomTag.FNUMBER + " got data " + fStop);
        } else {
            appendDebugMessage(CustomTag.FNUMBER + " could not find data");
        }

        appendDebugMessage("Constructing exif data object");
        Map<Tag, String> exifData = new HashMap<>();

        exifData.put(CustomTag.MODIFYDATE, modifiedTimestamp);

        if (takenTimestamp != null) {
            exifData.put(StandardTag.DATE_TIME_ORIGINAL, takenTimestamp);
        }

        if (photoMetaData.has("camera_make")) {
            exifData.put(StandardTag.MAKE, photoMetaData.getString("camera_make"));
            appendDebugMessage(StandardTag.MAKE + " got data " + photoMetaData.getString("camera_make"));
        } else {
            appendDebugMessage(StandardTag.MAKE + " could not find data");
        }
        if (photoMetaData.has("camera_model")) {
            exifData.put(StandardTag.MODEL, photoMetaData.getString("camera_model"));
            appendDebugMessage(StandardTag.MODEL + " got data " + photoMetaData.getString("camera_model"));
        } else {
            appendDebugMessage(StandardTag.MODEL + " could not find data");
        }

        if (photoMetaData.has("latitude") && photoMetaData.has("longitude")) {
            exifData.put(StandardTag.GPS_LATITUDE, photoMetaData.getString("latitude"));
            exifData.put(StandardTag.GPS_LATITUDE_REF, photoMetaData.getString("latitude"));
            exifData.put(StandardTag.GPS_LONGITUDE, photoMetaData.getString("longitude"));
            exifData.put(StandardTag.GPS_LONGITUDE_REF, photoMetaData.getString("longitude"));
            exifData.put(StandardTag.GPS_ALTITUDE, "0");
            exifData.put(StandardTag.GPS_ALTITUDE_REF, "0");
            appendDebugMessage(StandardTag.GPS_LATITUDE + " got data " + photoMetaData.getString("latitude"));
            appendDebugMessage(StandardTag.GPS_LONGITUDE + " got data " + photoMetaData.getString("longitude"));
        } else {
            appendDebugMessage("COORDINATES could not find data");
        }

        if (photoMetaData.has("exposure")) {
            exifData.put(CustomTag.EXPOSURE, photoMetaData.getString("exposure"));
            appendDebugMessage(CustomTag.EXPOSURE + " got data " + photoMetaData.getString("exposure"));
        } else {
            appendDebugMessage(CustomTag.EXPOSURE + " could not find data");
        }
        if (photoMetaData.has("iso_speed")) {
            exifData.put(StandardTag.ISO, photoMetaData.getString("iso_speed"));
            appendDebugMessage(StandardTag.ISO + " got data " + photoMetaData.getString("iso_speed"));
        } else {
            appendDebugMessage(StandardTag.ISO + " could not find data");
        }
        if (photoMetaData.has("focal_length")) {
            exifData.put(StandardTag.FOCAL_LENGTH, photoMetaData.getString("focal_length"));
            appendDebugMessage(StandardTag.FOCAL_LENGTH + " got data " + photoMetaData.getString("focal_length"));
        } else {
            appendDebugMessage(StandardTag.FOCAL_LENGTH + " could not find data");
        }
        if (fStop != null) {
            exifData.put(CustomTag.FNUMBER, fStop);
        }

        // This can be used to add more args to the execution of exiftool
        Format format = CustomFormat.DEFAULT;
        if (mainOptions.shouldOverwriteOriginals()) {
            format = CustomFormat.DEFAULT_OVERWRITE_ORIGINAL;
        }

        appendDebugMessage("calling setImageMeta for copied file: " + destFile.getName());
        exifTool.setImageMeta(destFile, format, exifData);
        
        // Set file creation and modified dates based on takenTimestamp
        if (takenTimestamp != null) {
            try {
                // Parse the takenTimestamp back to a Date object
                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss");
                Date takenDate = dateFormat.parse(takenTimestamp);
                long takenTime = takenDate.getTime();
                
                // Set both creation and modified times to the taken timestamp
                if (destFile.setLastModified(takenTime)) {
                    appendDebugMessage("Successfully set file modification time to taken timestamp: " + takenTimestamp);
                } else {
                    appendDebugMessage("Warning: Could not set file modification time");
                }
                
                // Try to set creation time using NIO (works on some systems)
                try {
                    java.nio.file.Files.setAttribute(
                        destFile.toPath(), 
                        "basic:creationTime", 
                        java.nio.file.attribute.FileTime.fromMillis(takenTime)
                    );
                    appendDebugMessage("Successfully set file creation time to taken timestamp: " + takenTimestamp);
                } catch (Exception e) {
                    appendDebugMessage("Warning: Could not set file creation time (this is normal on some systems): " + e.getMessage());
                }
                
            } catch (Exception e) {
                appendDebugMessage("Warning: Could not parse taken timestamp for file timestamps: " + e.getMessage());
            }
        }

        return true;
    }

    /**
     * Copies a file while preserving its metadata (creation time, modification time, etc.)
     * @param source The source file
     * @param dest The destination file
     * @throws IOException if the copy operation fails
     */
    private void copyFileWithMetadata(File source, File dest) throws IOException {
        // Use Java NIO for efficient file copying
        java.nio.file.Files.copy(
            source.toPath(), 
            dest.toPath(), 
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
        );
        
        // Explicitly preserve timestamps
        try {
            java.nio.file.Files.setAttribute(dest.toPath(), "basic:creationTime", 
                java.nio.file.Files.getAttribute(source.toPath(), "basic:creationTime"));
            java.nio.file.Files.setAttribute(dest.toPath(), "basic:lastModifiedTime", 
                java.nio.file.Files.getAttribute(source.toPath(), "basic:lastModifiedTime"));
            java.nio.file.Files.setAttribute(dest.toPath(), "basic:lastAccessTime", 
                java.nio.file.Files.getAttribute(source.toPath(), "basic:lastAccessTime"));
        } catch (Exception e) {
            // If we can't preserve all attributes, at least try to preserve the last modified time
            try {
                dest.setLastModified(source.lastModified());
            } catch (Exception ex) {
                appendDebugMessage("Warning: Could not preserve file timestamps for " + dest.getName());
            }
        }
    }

}
