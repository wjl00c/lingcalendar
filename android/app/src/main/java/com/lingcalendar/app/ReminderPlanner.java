package com.lingcalendar.app;

import android.app.*;
import android.content.*;
import android.os.Build;
import org.json.*;
import java.time.*;
import java.util.*;

final class ReminderPlanner {
 static final String ACTION="com.lingcalendar.app.REMIND";
 static boolean occurs(JSONObject i,LocalDate day){LocalDate start=LocalDate.parse(i.optString("Start")),end=LocalDate.parse(i.optString("End"));if(day.isBefore(start))return false;String repeat=i.optString("Repeat","none"),until=i.optString("Until","");if(repeat.equals("none"))return !day.isAfter(end);if(!until.isEmpty()&&day.isAfter(LocalDate.parse(until)))return false;return repeat.equals("daily")||repeat.equals("weekly")&&day.getDayOfWeek()==start.getDayOfWeek()||repeat.equals("monthly")&&day.getDayOfMonth()==start.getDayOfMonth()||repeat.equals("yearly")&&day.getMonth()==start.getMonth()&&day.getDayOfMonth()==start.getDayOfMonth();}
 static boolean done(JSONObject i,LocalDate day){String key=i.optString("Repeat","none").equals("none")?i.optString("Start"):day.toString();JSONArray dates=i.optJSONArray("DoneDates");if(dates!=null)for(int n=0;n<dates.length();n++)if(key.equals(dates.optString(n)))return true;return false;}
 static String signature(JSONObject i,LocalDate day){return day+"|"+i.optString("Id")+"|"+i.optString("Time")+"|"+i.optInt("Reminder",-1);}
 static PendingIntent pending(Context c,Intent i){return PendingIntent.getBroadcast(c,51,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
 static synchronized void schedule(Context c){try{
  AlarmManager alarms=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);Intent intent=new Intent(c,ReminderReceiver.class).setAction(ACTION);alarms.cancel(pending(c,intent));String raw=CryptoStore.read(c);if(raw==null)return;JSONObject records=new JSONObject(raw).optJSONObject("items");if(records==null)return;
  Set<String> fired=c.getSharedPreferences("reminders",0).getStringSet("fired",Collections.emptySet());long now=System.currentTimeMillis(),best=Long.MAX_VALUE;String id=null,dayKey=null,key=null;
  Iterator<String> ids=records.keys();while(ids.hasNext()){String candidateId=ids.next();JSONObject item=records.optJSONObject(candidateId);if(item==null||item.optInt("Reminder",-1)<0||item.optString("Time","").isEmpty())continue;
   try{LocalTime time=LocalTime.parse(item.getString("Time"));boolean once=item.optString("Repeat","none").equals("none");LocalDate first=once?LocalDate.parse(item.getString("Start")):LocalDate.now();for(int offset=0;offset<(once?1:1462);offset++){LocalDate day=first.plusDays(offset);if(day.getYear()>2099)break;if(!occurs(item,day)||done(item,day))continue;long trigger=ZonedDateTime.of(day,time,ZoneId.systemDefault()).minusMinutes(item.getInt("Reminder")).toInstant().toEpochMilli();String sig=signature(item,day);if(trigger<now-120000||fired.contains(sig))continue;if(trigger<best){best=trigger;id=candidateId;dayKey=day.toString();key=sig;}break;}}catch(Exception ignored){}
  }
  if(id!=null){intent.putExtra("id",id).putExtra("day",dayKey).putExtra("key",key);alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,Math.max(now+5000,best),pending(c,intent));}
 }catch(Exception ignored){}}
}
