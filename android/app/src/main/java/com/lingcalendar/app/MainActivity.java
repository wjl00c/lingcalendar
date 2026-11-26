package com.lingcalendar.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.webkit.*;
import android.widget.FrameLayout;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends Activity {
 private WebView web;private String pendingExport;private boolean readOnly=false;private LanNetwork network;
 @Override public void onCreate(Bundle saved){super.onCreate(saved);getWindow().setStatusBarColor(Color.rgb(246,243,251));getWindow().setNavigationBarColor(Color.WHITE);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
  FrameLayout frame=new FrameLayout(this);web=new WebView(this);frame.addView(web,new FrameLayout.LayoutParams(-1,-1));setContentView(frame);
  if(Build.VERSION.SDK_INT>=30){getWindow().setDecorFitsSystemWindows(false);frame.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});}
  WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
  network=new LanNetwork((id,result)->evaluate("window.LanNetwork&&LanNetwork.complete("+JSONObject.quote(id)+","+result.toString()+")"));web.addJavascriptInterface(new Bridge(),"Android");
  web.setWebViewClient(new WebViewClient(){
   @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return !"lingcalendar.local".equals(request.getUrl().getHost());}
   @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){Uri uri=request.getUrl();if(!"lingcalendar.local".equals(uri.getHost()))return null;String path=uri.getPath();if(path==null||path.contains(".."))return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));String name=path.startsWith("/")?path.substring(1):path;if(name.isEmpty())name="index.html";try{String mime=name.endsWith(".js")?"application/javascript":name.endsWith(".css")?"text/css":"text/html";return new WebResourceResponse(mime,"UTF-8",getAssets().open(name));}catch(IOException e){return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));}}
   @Override public void onPageFinished(WebView view,String url){evaluate("window.CalendarApp&&CalendarApp.resume()");}
  });
  web.setWebChromeClient(new WebChromeClient(){@Override public boolean onJsConfirm(WebView view,String url,String message,JsResult result){new AlertDialog.Builder(MainActivity.this).setMessage(message).setPositiveButton("确定",(d,w)->result.confirm()).setNegativeButton("取消",(d,w)->result.cancel()).setOnCancelListener(d->result.cancel()).show();return true;}});
  web.loadUrl("https://lingcalendar.local/index.html");
 }
 private void evaluate(String code){runOnUiThread(()->{if(web!=null)web.evaluateJavascript(code,null);});}
 @Override protected void onResume(){super.onResume();if(web!=null){web.onResume();evaluate("window.CalendarApp&&CalendarApp.resume()");}}
 @Override protected void onPause(){if(web!=null){evaluate("window.CalendarApp&&CalendarApp.pause()");web.onPause();}super.onPause();}
 @Override protected void onDestroy(){if(network!=null)network.close();if(web!=null){web.removeJavascriptInterface("Android");web.destroy();web=null;}super.onDestroy();}
 private final class Bridge {
  @JavascriptInterface public void networkRequest(String json){network.request(json);}
  @JavascriptInterface public String responseChunk(String id,int offset){return network.chunk(id,offset);}
  @JavascriptInterface public void releaseResponse(String id){network.release(id);}
  @JavascriptInterface public String loadState(){try{return CryptoStore.read(MainActivity.this);}catch(Exception e){readOnly=true;return "{\"__error\":\"本机资料无法读取，已停止覆盖保存。请保留应用数据并联系修复。\"}";}}
  @JavascriptInterface public String saveState(String text){if(readOnly)return "本机资料处于只读保护状态";try{JSONObject data=new JSONObject(text);if(data.optInt("version")!=1||data.optJSONObject("items")==null)return "资料格式错误";CryptoStore.write(MainActivity.this,text);ReminderPlanner.schedule(MainActivity.this);return "ok";}catch(Exception e){return "本机保存失败："+e.getMessage();}}
  @JavascriptInterface public void exportBackup(String text){if(text.length()>16000000)return;pendingExport=text;runOnUiThread(()->{Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"灵动月历备份.json");startActivityForResult(i,21);});}
  @JavascriptInterface public void importBackup(){runOnUiThread(()->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE),22));}
  @JavascriptInterface public void requestNotifications(){runOnUiThread(()->{if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},23);else evaluate("CalendarApp.notificationResult(true)");});}
 }
 @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);if(code==23){boolean ok=results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED;evaluate("CalendarApp.notificationResult("+ok+")");}}
 @Override protected void onActivityResult(int code,int result,Intent data){super.onActivityResult(code,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;try{
  if(code==21&&pendingExport!=null){try(OutputStream out=getContentResolver().openOutputStream(data.getData(),"wt")){if(out==null)throw new IOException("无法打开导出位置");out.write(pendingExport.getBytes(StandardCharsets.UTF_8));}pendingExport=null;}
  if(code==22){ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=getContentResolver().openInputStream(data.getData())){if(in==null)throw new IOException("无法读取备份");byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>16000000)throw new IOException("备份过大");out.write(b,0,n);}}evaluate("CalendarApp.importBackup("+JSONObject.quote(out.toString("UTF-8"))+")");}
 }catch(Exception ex){new AlertDialog.Builder(this).setMessage("备份操作未完成："+ex.getMessage()).setPositiveButton("确定",null).show();}}
}
