# Message Processing Feature

This document describes the new message processing feature that extracts `creation_timestamp` data from photos and videos in Facebook message JSON files.

## Overview

The Facebook data export includes message conversations in JSON format, which may contain photos and videos with metadata including `creation_timestamp`. This feature extends the existing album processing functionality to also process these message files.

## Directory Structure

The feature looks for message files in the following structure:
```
facebook-data-export/
├── messages/
│   ├── inbox/
│   │   └── conversation_folder/
│   │       ├── message_1.json
│   │       ├── message_2.json
│   │       └── photos/
│   └── e2ee_cutover/
│       └── conversation_folder/
│           ├── message_1.json
│           └── photos/
```

## Message JSON Structure

Message files contain an array of messages, where each message may include:

### Photos
```json
{
  "sender_name": "User Name",
  "timestamp_ms": 1532991928112,
  "photos": [
    {
      "uri": "your_facebook_activity/messages/inbox/conversation/photos/image.jpg",
      "creation_timestamp": 1532991926
    }
  ]
}
```

### Videos
```json
{
  "sender_name": "User Name", 
  "timestamp_ms": 1532991928112,
  "videos": [
    {
      "uri": "your_facebook_activity/messages/inbox/conversation/videos/video.mp4",
      "creation_timestamp": 1532991926
    }
  ]
}
```

### Files (All Files with creation_timestamp)
```json
{
  "sender_name": "User Name",
  "timestamp_ms": 1532991928112,
  "files": [
    {
      "uri": "your_facebook_activity/messages/inbox/conversation/files/document.pdf",
      "creation_timestamp": 1532991926
    },
    {
      "uri": "your_facebook_activity/messages/inbox/conversation/files/video.mp4",
      "creation_timestamp": 1532991926
    }
  ]
}
```

## Implementation Details

### Processing Flow

1. **Directory Discovery**: The system searches for `messages/inbox` and `messages/e2ee_cutover` directories
2. **Conversation Processing**: For each conversation folder, it processes all JSON files
3. **Message Parsing**: Each JSON file is parsed to extract the `messages` array
4. **Media Extraction**: For each message, it looks for:
   - `photos` array
   - `videos` array  
   - `files` array (all files with creation_timestamp) - only if file processing is enabled
5. **Timestamp Processing**: Uses the same `processFile()` method as album processing to extract and apply `creation_timestamp`

### Supported Video Formats

The system recognizes the following video file extensions:
- `.mp4`, `.avi`, `.mov`, `.wmv`, `.flv`, `.webm`, `.mkv`, `.m4v`, `.3gp`

### Integration with Existing Code

The message processing uses the same `processFile()` method as album processing, ensuring:
- Consistent timestamp extraction logic
- Same EXIF data application
- Same file modification behavior
- Same error handling and reporting

### Conversation Naming

Conversation folders are automatically named based on the participants in the conversation:
- Extracts participant names from the `participants` array in message JSON files
- Combines names with underscores (e.g., "Mike Lane" + "Dom Scott" = "Mike_Lane_Dom_Scott")
- Sanitizes names to be filesystem-safe (removes invalid characters)
- Falls back to the conversation directory name if participant names cannot be extracted

## Usage

The message processing runs automatically after album processing when you run the application. It will:

1. Process all albums first (existing functionality)
2. Process all messages for photos and videos (new functionality)
3. Optionally process files (if the "Process files" option is enabled)
4. Copy all media files to organized conversation folders under `Processed/Messages/`
5. Display summary statistics for both operations

## Output

The system provides detailed logging including:
- Number of conversations processed
- Number of photos found and processed
- Number of videos found and processed
- Number of files found and processed
- Number of conversations copied to organized folders
- Any errors encountered during processing

Example output:
```
Looking for messages...
File processing: enabled (will process photos, videos, and files)
Processing inbox messages...
Processing conversation: mikelane_10155781651915802
Loading message file: .../message_1.json
Processing 50 messages in message_1.json
Copied 7 files to 'Mike_Lane_Dom_Scott' conversation folder
Message processing complete:
Photos processed: 5
Videos processed: 1
Files processed: 1
Failed: 0
Conversations copied to Processed/Messages folder: 1
```

Or when file processing is disabled:
```
Looking for messages...
File processing: disabled (will only process photos and videos)
Processing inbox messages...
Processing conversation: mikelane_10155781651915802
Loading message file: .../message_1.json
Processing 50 messages in message_1.json
Copied 6 files to 'Mike_Lane_Dom_Scott' conversation folder
Message processing complete:
Photos processed: 5
Videos processed: 1
Files processing: disabled
Failed: 0
Conversations copied to Processed/Messages folder: 1
```

## File Organization

When the "Copy to new folders" option is enabled, the system creates the following structure:

```
facebook-data-export/
├── Processed/
│   ├── Messages/
│   │   ├── Mike_Lane_Dom_Scott/
│   │   │   ├── photo1.jpg
│   │   │   ├── photo2.jpg
│   │   │   ├── video1.mp4
│   │   │   └── document.pdf
│   │   └── Another_Conversation/
│   │       └── ...
│   └── Album_Name/
│       └── ...
```

## Error Handling

The system handles various error conditions:
- Missing message directories
- Invalid JSON files
- Missing photo/video files
- File permission issues
- Corrupted metadata

All errors are logged and processing continues with the next file.

## Technical Notes

- Uses the same `creation_timestamp` extraction logic as album processing
- Maintains backward compatibility with existing functionality
- Follows the same file path resolution logic
- Integrates with the existing UI and progress reporting
- Supports the same dry-run and debug modes
- File processing can be enabled/disabled via the UI toggle
