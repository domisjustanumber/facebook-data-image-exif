package com.github.addshore.facebook.data.image.exif;

public class MainOptions {

    private final Boolean debug;
    private final Boolean dry;
    private final Boolean overwriteOriginals;
    private final Boolean copyToNewFolders;
    private final Boolean processPosts;
    private final Boolean processMessages;
    private final Boolean processFiles;

    public MainOptions(
            Boolean debug,
            Boolean dry,
            Boolean overwriteOriginals,
            Boolean copyToNewFolders,
            Boolean processPosts,
            Boolean processMessages,
            Boolean processFiles
    ) {
        this.debug = debug;
        this.dry = dry;
        this.overwriteOriginals = overwriteOriginals;
        this.copyToNewFolders = copyToNewFolders;
        this.processPosts = processPosts;
        this.processMessages = processMessages;
        this.processFiles = processFiles;
    }

    public Boolean isDryMode() {
        return dry;
    }

    public Boolean isDebugMode() {
        return debug;
    }

    public Boolean shouldOverwriteOriginals() {
        return overwriteOriginals;
    }

    public Boolean shouldCopyToNewFolders() {
        return copyToNewFolders;
    }

    public Boolean shouldProcessPosts() {
        return processPosts;
    }

    public Boolean shouldProcessMessages() {
        return processMessages;
    }

    public Boolean shouldProcessFiles() {
        return processFiles;
    }

}
