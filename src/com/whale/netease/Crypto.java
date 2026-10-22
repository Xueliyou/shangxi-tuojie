package com.whale.netease;

import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** 前端资源解密（AES-256-CBC，文件头 16 字节 IV） */
public final class Crypto {

    // 32 字节密钥（构建时同一份，用于加密 assets/payload.bin）
    private static final byte[] KEY = {
            0x7a, 0x1e, 0x53, 0x2b, (byte) 0x9c, 0x40, (byte) 0xd7, 0x66,
            0x0f, (byte) 0xb2, 0x35, (byte) 0x8a, 0x51, (byte) 0xe4, 0x29, (byte) 0xc8,
            0x6d, 0x03, (byte) 0x97, 0x1c, (byte) 0xaf, 0x58, 0x2e, (byte) 0xd1,
            0x44, (byte) 0x86, 0x0b, (byte) 0xe9, 0x3f, (byte) 0xc5, 0x72, (byte) 0x9d
    };

    private Crypto() {}

    public static byte[] decrypt(byte[] data) throws Exception {
        if (data == null || data.length <= 16) throw new Exception("payload 损坏");
        byte[] iv = Arrays.copyOf(data, 16);
        byte[] body = Arrays.copyOfRange(data, 16, data.length);
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY, "AES"), new IvParameterSpec(iv));
        return c.doFinal(body);
    }

    /** 简单校验值，用于判断是否需要重新解包 */
    public static String hash(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(data == null ? 0 : data.length);
        }
    }
}
