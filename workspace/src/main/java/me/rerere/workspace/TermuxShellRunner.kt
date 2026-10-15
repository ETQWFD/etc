package me.rerere.workspace

import java.io.File

/**
 * Termux 布局（bootstrap 包，usr/bin/bash）专用执行器。
 * Termux bootstrap 内的 bash 与工具链都是 Android bionic 原生 ELF，
 * 无需 PRoot、无需 root，直接以宿主机进程方式执行；通过设置
 * PREFIX / LD_LIBRARY_PATH / PATH / HOME 让工具链找到各自的库与命令。
 */
class TermuxShellRunner : WorkspaceShellRunner {
    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        val linuxDir = context.linuxDir
        val prefix = File(linuxDir, "usr")
        val bash = File(prefix, "bin/bash")
        if (!bash.isFile) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "Termux bash not found: ${bash.absolutePath}",
            )
        }

        val home = File(linuxDir, "home").apply { mkdirs() }
        context.tempDir.mkdirs()

        val process = ProcessBuilder(
            bash.absolutePath,
            "-c",
            // 命令通过位置参数传入, 避免转义; 先切到工作目录再执行
            "cd -- \"\$1\" && eval \"\$2\"",
            "rikkahub",
            context.workingDir.absolutePath,
            context.command,
        )
            .directory(context.workingDir)
            .redirectErrorStream(false)
            .apply {
                environment()["PREFIX"] = prefix.absolutePath
                environment()["LD_LIBRARY_PATH"] = "${prefix.absolutePath}/lib"
                environment()["PATH"] = "${prefix.absolutePath}/bin"
                environment()["HOME"] = home.absolutePath
                environment()["TERM"] = "xterm-256color"
                environment()["LANG"] = "C.UTF-8"
                environment()["LC_ALL"] = "C.UTF-8"
                environment()["TMPDIR"] = context.tempDir.absolutePath
                environment()["CI"] = "true"
                environment()["NO_COLOR"] = "1"
                environment()["PAGER"] = "cat"
                // Android 原生二进制需要 ld library path 传入 linker
                environment()["TERMUX_APP__LD_LIBRARY_PATH"] = "${prefix.absolutePath}/lib"
            }
            .start()

        return process.readResult(context.timeoutMillis, context.stdin)
    }
}
