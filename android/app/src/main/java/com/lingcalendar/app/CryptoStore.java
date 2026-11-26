package com.lingcalendar.app;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

final class CryptoStore {
 private static final String ALIAS="lingcalendar.state.v1";
 private static SecretKey key() throws Exception {
  KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
  if(store.containsAlias(ALIAS))return (SecretKey)store.getKey(ALIAS,null);
  KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
  generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());return generator.generateKey();
 }
 private static AtomicFile file(Context context){return new AtomicFile(new File(context.getNoBackupFilesDir(),"calendar-state.enc"));}
 static synchronized String read(Context context)throws Exception {
  AtomicFile f=file(context);if(!f.getBaseFile().exists()&&!new File(f.getBaseFile()+".bak").exists())return null;
  byte[] bytes;try(InputStream in=f.openRead();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>16000000)throw new IOException("本机资料过大");out.write(b,0,n);}bytes=out.toByteArray();}
  try(DataInputStream input=new DataInputStream(new ByteArrayInputStream(bytes))){if(input.readInt()!=1)throw new IOException("未知资料版本");int length=input.readInt();if(length<12||length>16)throw new IOException("无效加密资料");byte[] iv=new byte[length];input.readFully(iv);byte[] encrypted=new byte[input.available()];input.readFully(encrypted);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,iv));return new String(cipher.doFinal(encrypted),StandardCharsets.UTF_8);}
 }
 static synchronized void write(Context context,String text)throws Exception {
  byte[] plain=text.getBytes(StandardCharsets.UTF_8);if(plain.length>15000000)throw new IOException("本机资料过大，请先导出归档");
  Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());byte[] iv=cipher.getIV(),encrypted=cipher.doFinal(plain);
  AtomicFile f=file(context);FileOutputStream output=null;
  try{output=f.startWrite();DataOutputStream data=new DataOutputStream(output);data.writeInt(1);data.writeInt(iv.length);data.write(iv);data.write(encrypted);data.flush();f.finishWrite(output);}catch(Exception ex){if(output!=null)f.failWrite(output);throw ex;}
 }
}
