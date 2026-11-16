package app.contactrepair.core

/**
 * Top-level repair entry point (matches the frozen public API).
 */
fun repair(files: List<NamedBytes>, options: RepairOptions): RepairResult =
    RepairEngine.repair(files, options)
