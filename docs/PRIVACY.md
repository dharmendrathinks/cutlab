# Privacy and local data

CutLab has no account system, analytics SDK, advertising SDK, cloud backend, or Android Internet permission. Editing and export run locally.

- Selected media is copied into the app's private project directory. Importing does not modify the original.
- Projects autosave locally with one previous checkpoint. Android cloud backup is disabled for the app.
- Microphone permission is used only for the recording feature. You can deny or revoke it in Android settings.
- Completed videos are published to `Movies/CutLab` through Android MediaStore. Other apps with appropriate access can see these files.
- Project archives include project metadata and copied source media. They may include original filenames and media metadata. Share them deliberately.
- Sharing or opening an exported video hands its URI to an app you choose. That app has its own data handling and may use the network.
- Deleting a project removes its owned media. Original imports and completed exported videos remain. Uninstalling CutLab removes private project data; export archives first if you need backups.

Issue attachments, logs, and GitHub Actions artifacts are separate from the app. Reports should use synthetic media and redact personal paths, names, and identifiers. This statement describes the current source; modified builds may behave differently.
