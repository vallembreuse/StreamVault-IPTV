# Keep the command-line entrypoint name so app_process can invoke it after R8.
-keep class com.streamvault.sftpdiagnostic.Runner {
    public static void main(java.lang.String[]);
}
