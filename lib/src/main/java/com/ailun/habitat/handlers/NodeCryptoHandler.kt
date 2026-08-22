package com.ailun.habitat.handlers

import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * [ACTION_CRYPTO]：文件 AES-GCM 加密/解密（零权限，纯 JVM）。
 * params：
 * - `action`（必填）："encrypt" / "decrypt"
 * - `path`（必填）：encrypt=明文文件；decrypt=密文文件
 * - `output_path`（可选）：输出路径；默认 encrypt=path+".enc"，decrypt=去掉 ".enc"
 * - `password`（必填）：口令
 * - `output_var`（可选）：结果变量名，默认 "crypto_result"
 * 输出：crypto_result（输出路径）、crypto_success、crypto_error
 *
 * 文件格式：salt(16B) + IV(12B) + AES-GCM 密文；密钥 = PBKDF2WithHmacSHA256(password, salt, 100k, 256bit)。
 */
class NodeCryptoHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val action = node.params?.get("action")?.toString()?.trim()?.lowercase() ?: "encrypt"
        val rawPath = node.params?.get("path")?.toString()?.trim().orEmpty()
        if (rawPath.isEmpty()) {
            context.variables["crypto_success"] = false
            context.variables["crypto_error"] = "缺少 'path' 参数"
            return node.next
        }
        val path = context.interpolate(rawPath)
        val password = node.params?.get("password")?.toString().orEmpty()
        if (password.isEmpty()) {
            context.variables["crypto_success"] = false
            context.variables["crypto_error"] = "缺少 'password' 参数"
            return node.next
        }
        val outputVar = node.params?.get("output_var")?.toString()?.trim()?.ifEmpty { null }
            ?: "crypto_result"
        val outputPath = node.params?.get("output_path")?.toString()?.let { context.interpolate(it) }
            ?: if (action == "encrypt") "$path.enc" else path.removeSuffix(".enc")

        try {
            val file = File(path)
            if (!file.exists() || !file.isFile) throw IllegalArgumentException("文件不存在: $path")

            val output = when (action) {
                "encrypt" -> encryptFile(file, File(outputPath), password)
                "decrypt" -> decryptFile(file, File(outputPath), password)
                else -> throw IllegalArgumentException("未知 action: $action（encrypt/decrypt）")
            }

            context.variables[outputVar] = output.absolutePath
            context.variables["crypto_success"] = true
            context.log("Crypto $action → ${output.absolutePath}")
        } catch (e: Exception) {
            context.variables["crypto_success"] = false
            context.variables["crypto_error"] = e.message ?: "Unknown error"
            context.log("Crypto FAILED: ${e.message}")
        }
        return node.next
    }

    private fun encryptFile(input: File, output: File, password: String): File {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))

        output.parentFile?.mkdirs()
        output.outputStream().use { out ->
            out.write(salt)
            out.write(iv)
            input.inputStream().use { ins ->
                val buf = ByteArray(8192)
                var read: Int
                while (ins.read(buf).also { read = it } != -1) {
                    val enc = cipher.update(buf, 0, read)
                    if (enc != null) out.write(enc)
                }
                out.write(cipher.doFinal())
            }
        }
        return output
    }

    private fun decryptFile(input: File, output: File, password: String): File {
        val bytes = input.readBytes()
        if (bytes.size < 28) throw IllegalArgumentException("密文文件格式错误（太短）")
        val salt = bytes.copyOfRange(0, 16)
        val iv = bytes.copyOfRange(16, 28)
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))

        output.parentFile?.mkdirs()
        output.outputStream().use { out ->
            out.write(cipher.doFinal(bytes, 28, bytes.size - 28))
        }
        return output
    }

    private fun deriveKey(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, 100_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }
}
