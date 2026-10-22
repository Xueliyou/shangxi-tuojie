package com.whale.netease;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 网易云 eapi 加密实现。
 * 参考 Suxiaoqinx/Netease_url 的 CryptoUtils（MIT）。
 *
 * params = f"{path}-36cd479b6b5-{json}-36cd479b6b5-{md5('nobody'+path+'use'+json+'md5forencrypt')}"
 * 再用 AES-128-ECB/PKCS7 加密并转十六进制小写。
 */
public final class Eapi {

    /** eapi 固定密钥 */
    private static final byte[] KEY = "e82ckenh8dichen8".getBytes(StandardCharsets.UTF_8);
    private static final String SEP = "-36cd479b6b5-";

    private Eapi() {}

    public static String toHex(byte[] data) {
        char[] hex = new char[data.length * 2];
        final char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < data.length; i++) {
            int v = data[i] & 0xFF;
            hex[i * 2] = digits[v >>> 4];
            hex[i * 2 + 1] = digits[v & 0x0F];
        }
        return new String(hex);
    }

    public static String md5Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return toHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("MD5 不可用", e);
        }
    }

    /** 把 /eapi/ 路径换成 /api/ —— 网易云摘要算法要求 */
    public static String apiPath(String url) {
        String path;
        try {
            path = new URL(url).getPath();
        } catch (Exception e) {
            int q = url.indexOf("://");
            String rest = q >= 0 ? url.substring(q + 3) : url;
            int slash = rest.indexOf('/');
            path = slash >= 0 ? rest.substring(slash) : "/";
        }
        int qm = path.indexOf('?');
        if (qm >= 0) path = path.substring(0, qm);
        return path.replace("/eapi/", "/api/");
    }

    /**
     * 生成 eapi 请求的 params 十六进制串。
     *
     * @param url         请求的 eapi 地址
     * @param jsonPayload 明文 JSON（本方法内部不再改动，直接参与摘要与加密）
     */
    public static String encrypt(String url, String jsonPayload) throws Exception {
        String path = apiPath(url);
        String digest = md5Hex("nobody" + path + "use" + jsonPayload + "md5forencrypt");
        String plain = path + SEP + jsonPayload + SEP + digest;

        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, "AES"));
        return toHex(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
    }
}
