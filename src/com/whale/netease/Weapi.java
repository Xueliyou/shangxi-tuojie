package com.whale.netease;

import android.util.Base64;

import org.json.JSONObject;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 网易云 weapi 加密。
 * params = base64(aes(aes(json, nonce), secretKey))
 * encSecKey = rsa(secretKey 反转)
 */
public final class Weapi {

    private static final String NONCE = "0CoJUm6Qyw8W8jud";
    private static final String IV = "0102030405060708";
    private static final String PUBKEY = "010001";
    private static final String MODULUS =
            "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4"
          + "ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d81"
          + "3cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7";

    private Weapi() {}

    /** 返回 {params, encSecKey} */
    public static String[] encrypt(JSONObject data) throws Exception {
        String text = data.toString();
        String secretKey = randomKey();
        String params = aesBase64(aesBase64(text, NONCE), secretKey);
        return new String[]{params, rsa(secretKey)};
    }

    private static String randomKey() {
        final String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        Random r = new Random();
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }

    private static String aesBase64(String text, String key) throws Exception {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        int pad = 16 - (data.length % 16);
        byte[] padded = new byte[data.length + pad];
        System.arraycopy(data, 0, padded, 0, data.length);
        for (int i = data.length; i < padded.length; i++) padded[i] = (byte) pad;

        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"),
                new IvParameterSpec(IV.getBytes(StandardCharsets.UTF_8)));
        return Base64.encodeToString(cipher.doFinal(padded), Base64.NO_WRAP);
    }

    private static String rsa(String text) {
        String reversed = new StringBuilder(text).reverse().toString();
        BigInteger m = new BigInteger(MODULUS, 16);
        BigInteger e = new BigInteger(PUBKEY, 16);
        BigInteger msg = new BigInteger(1, reversed.getBytes(StandardCharsets.UTF_8));
        String hex = msg.modPow(e, m).toString(16);
        StringBuilder sb = new StringBuilder(hex);
        while (sb.length() < 256) sb.insert(0, '0');
        return sb.toString();
    }
}
