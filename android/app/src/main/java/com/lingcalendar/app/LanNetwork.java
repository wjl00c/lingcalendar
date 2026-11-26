package com.lingcalendar.app;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

final class LanNetwork {
 interface Callback{void complete(String id,JSONObject result);}
 private final ExecutorService executor=Executors.newFixedThreadPool(3);private final Map<String,String> bodies=new ConcurrentHashMap<>();private final Callback callback;
 LanNetwork(Callback callback){this.callback=callback;}
 static String fingerprint(java.security.cert.Certificate cert)throws Exception{byte[] hash=MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());StringBuilder s=new StringBuilder();for(byte b:hash)s.append(String.format(Locale.ROOT,"%02X",b&255));return s.toString();}
 static URL address(String value)throws Exception{URL u=new URL(value);if(!u.getProtocol().equals("https")||u.getUserInfo()!=null)throw new IOException("仅支持 HTTPS 同步地址");return u;}
 void request(String json){executor.execute(()->{String id="";JSONObject result=new JSONObject();try{JSONObject data=new JSONObject(json);id=data.getString("id");if(!id.matches("[A-Za-z0-9-]{1,100}"))return;URL url=address(data.getString("url"));
  if(data.optBoolean("probe")){final String[] pin={null};TrustManager[] capture={new X509TrustManager(){public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}public void checkClientTrusted(X509Certificate[] chain,String type)throws CertificateException{throw new CertificateException("not a client");}public void checkServerTrusted(X509Certificate[] chain,String type)throws CertificateException{try{if(chain.length>0)pin[0]=fingerprint(chain[0]);}catch(Exception e){throw new CertificateException(e);}throw new CertificateException("identity probe only; no credentials transmitted");}}};SSLContext ctx=SSLContext.getInstance("TLS");ctx.init(null,capture,null);try(SSLSocket socket=(SSLSocket)ctx.getSocketFactory().createSocket()){socket.connect(new InetSocketAddress(url.getHost(),url.getPort()<0?443:url.getPort()),10000);socket.setSoTimeout(10000);try{socket.startHandshake();}catch(SSLException expected){if(pin[0]==null)throw expected;}}if(pin[0]==null)throw new IOException("未能读取电脑身份");result.put("pin",pin[0]);
  }else{
   String expected=data.getString("pin").toUpperCase(Locale.ROOT);if(!expected.matches("[A-F0-9]{64}"))throw new IOException("请先核对电脑配对码");
   TrustManager[] trust={new X509TrustManager(){public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}public void checkClientTrusted(X509Certificate[] chain,String type)throws CertificateException{throw new CertificateException("not a client");}public void checkServerTrusted(X509Certificate[] chain,String type)throws CertificateException{try{if(chain.length==0||!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),fingerprint(chain[0]).getBytes(StandardCharsets.US_ASCII)))throw new CertificateException("电脑身份已改变，请重新配对");chain[0].checkValidity();}catch(CertificateException e){throw e;}catch(Exception e){throw new CertificateException(e);}}}};
   SSLContext ctx=SSLContext.getInstance("TLS");ctx.init(null,trust,null);HttpsURLConnection conn=(HttpsURLConnection)url.openConnection();conn.setSSLSocketFactory(ctx.getSocketFactory());conn.setHostnameVerifier((host,session)->{try{return host.equalsIgnoreCase(url.getHost())&&expected.equals(fingerprint(session.getPeerCertificates()[0]));}catch(Exception e){return false;}});conn.setInstanceFollowRedirects(false);conn.setConnectTimeout(10000);conn.setReadTimeout(15000);conn.setRequestMethod(data.optString("method","GET"));JSONObject headers=data.optJSONObject("headers");if(headers!=null){Iterator<String> keys=headers.keys();while(keys.hasNext()){String k=keys.next();if(k.equalsIgnoreCase("apikey")||k.equalsIgnoreCase("Authorization")||k.equalsIgnoreCase("Content-Type"))conn.setRequestProperty(k,headers.getString(k));}}
   String body=data.optString("body","");if(!body.isEmpty()){byte[] bytes=body.getBytes(StandardCharsets.UTF_8);conn.setDoOutput(true);conn.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=conn.getOutputStream()){out.write(bytes);}}
   int status=conn.getResponseCode();try(InputStream in=status>=400?conn.getErrorStream():conn.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){if(in!=null){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>20000000)throw new IOException("同步响应过大");out.write(b,0,n);}}String text=out.toString("UTF-8");bodies.put(id,text);result.put("status",status);result.put("length",text.length());}finally{conn.disconnect();}
  }
 }catch(Exception e){try{result.put("error",e.getMessage()==null?"无法连接电脑，请检查同一 Wi-Fi 与电脑同步开关":e.getMessage());}catch(JSONException ignored){}}callback.complete(id,result);});}
 String chunk(String id,int offset){String text=bodies.get(id);if(text==null||offset<0||offset>text.length())return "";return text.substring(offset,Math.min(text.length(),offset+65536));}
 void release(String id){bodies.remove(id);}
 void close(){executor.shutdownNow();bodies.clear();}
}
