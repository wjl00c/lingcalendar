package com.lingcalendar.app;
import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import org.json.*;
import java.time.LocalDate;
import java.util.*;

public final class ReminderReceiver extends BroadcastReceiver {
 @Override public void onReceive(Context c,Intent intent){if(!ReminderPlanner.ACTION.equals(intent.getAction()))return;try{
  String raw=CryptoStore.read(c);if(raw==null)return;JSONObject items=new JSONObject(raw).optJSONObject("items");String id=intent.getStringExtra("id"),day=intent.getStringExtra("day"),key=intent.getStringExtra("key");JSONObject item=items==null?null:items.optJSONObject(id);if(item==null||day==null||key==null)return;
  LocalDate date=LocalDate.parse(day);if(!ReminderPlanner.occurs(item,date)||ReminderPlanner.done(item,date)||!key.equals(ReminderPlanner.signature(item,date)))return;
  Set<String> fired=new HashSet<>(c.getSharedPreferences("reminders",0).getStringSet("fired",Collections.emptySet()));if(!fired.add(key))return;List<String> sorted=new ArrayList<>(fired);Collections.sort(sorted);if(sorted.size()>1000)sorted=sorted.subList(sorted.size()-1000,sorted.size());c.getSharedPreferences("reminders",0).edit().putStringSet("fired",new HashSet<>(sorted)).apply();
  NotificationManager manager=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);manager.createNotificationChannel(new NotificationChannel("calendar","日程提醒",NotificationManager.IMPORTANCE_DEFAULT));
  PendingIntent open=PendingIntent.getActivity(c,0,new Intent(c,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
  Notification n=new Notification.Builder(c,"calendar").setSmallIcon(android.R.drawable.ic_menu_my_calendar).setContentTitle(item.optString("Title")).setContentText(day+" "+item.optString("Time")+" "+item.optString("Notes")).setContentIntent(open).setAutoCancel(true).build();
  if(Build.VERSION.SDK_INT<33||c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)manager.notify(key.hashCode(),n);
 }catch(Exception ignored){}finally{ReminderPlanner.schedule(c);}}
}
