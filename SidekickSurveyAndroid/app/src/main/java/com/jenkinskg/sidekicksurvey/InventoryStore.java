package com.jenkinskg.sidekicksurvey;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local offline inventory. No web requests, no inferred MAC/BSSID relationship. */
public final class InventoryStore {
    private InventoryStore(){}
    public static final String FILE="aruba_ap_inventory.json";
    private static final Pattern MAC=Pattern.compile("(?i)(?<![a-f0-9])(?:(?:[a-f0-9]{2}[:-]){5}[a-f0-9]{2}|(?:[a-f0-9]{4}\\.){2}[a-f0-9]{4}|[a-f0-9]{12})(?![a-f0-9])");
    private static final Pattern IPV4=Pattern.compile("(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])");
    public static final class Entry {
        public String mac="",ip="",name="";
        public final ArrayList<String> bssids=new ArrayList<>();
        public String displayName(){return name.isEmpty()?"AP "+mac:name;}
        public String displayLine(){return displayName()+"\nIP "+(ip.isEmpty()?"unknown":ip)+"  •  Wired MAC "+mac+(bssids.isEmpty()?"":"\nBSSIDs: "+String.join(", ",bssids));}
    }
    public static String normalizeMac(String value){
        if(value==null)return "";
        String chars=value.replaceAll("[^A-Fa-f0-9]","").toUpperCase(Locale.US);
        if(chars.length()!=12)return "";
        return chars.substring(0,2)+":"+chars.substring(2,4)+":"+chars.substring(4,6)+":"+
            chars.substring(6,8)+":"+chars.substring(8,10)+":"+chars.substring(10,12);
    }
    public static boolean validIp(String value){
        String[] p=value.split("\\.");
        if(p.length!=4)return false;
        try{for(String part:p){int v=Integer.parseInt(part);if(v<0||v>255)return false;}}
        catch(NumberFormatException e){return false;}
        return true;
    }
    public static ArrayList<Entry> parse(String text){
        LinkedHashMap<String,Entry> entries=new LinkedHashMap<>();
        if(text==null)return new ArrayList<>();
        for(String line:text.replace("\uFEFF","").split("\\R")){
            line=line.trim();
            if(line.isEmpty()||line.startsWith("#")||line.startsWith("//"))continue;
            Matcher mm=MAC.matcher(line);
            ArrayList<String> macs=new ArrayList<>();
            while(mm.find()){
                String normalized=normalizeMac(mm.group());
                if(!normalized.isEmpty()&&!macs.contains(normalized))macs.add(normalized);
            }
            if(macs.isEmpty())continue;
            Matcher ipMatch=IPV4.matcher(line);
            String ip="";
            while(ipMatch.find()){if(validIp(ipMatch.group())){ip=ipMatch.group();break;}}
            String name=MAC.matcher(line).replaceAll(" ");
            name=IPV4.matcher(name).replaceAll(" ");
            name=name.replaceAll("(?i)\\b(?:AP[-_ ]?NAME|HOSTNAME|MAC|IP|IPV4|ETHERNET|BSSID|NAME)\\b\\s*[:=]?"," ");
            name=name.replaceAll("[\\s,;\\t\"'|]+"," ").trim();
            if(name.equals("-")||name.equalsIgnoreCase("ap"))name="";
            Entry old=entries.get(macs.get(0));
            if(old==null){
                old=new Entry();old.mac=macs.get(0);
                entries.put(old.mac,old);
            }
            if(!ip.isEmpty())old.ip=ip;
            if(!name.isEmpty())old.name=name;
            if(macs.size()>1)for(int i=1;i<macs.size();i++){
                if(!old.bssids.contains(macs.get(i)))old.bssids.add(macs.get(i));
            }
        }
        return new ArrayList<>(entries.values());
    }
    public static ArrayList<Entry> load(Context context){
        ArrayList<Entry> out=new ArrayList<>();
        try{
            File file=new File(context.getFilesDir(),FILE);
            if(!file.exists())return out;
            byte[] raw=java.nio.file.Files.readAllBytes(file.toPath());
            JSONArray list=new JSONArray(new String(raw,StandardCharsets.UTF_8));
            for(int i=0;i<list.length();i++){
                JSONObject x=list.getJSONObject(i);
                Entry e=new Entry();e.mac=normalizeMac(x.optString("mac"));
                if(e.mac.isEmpty())continue;
                e.name=x.optString("name");e.ip=x.optString("ip");
                JSONArray bs=x.optJSONArray("bssids");
                if(bs!=null)for(int j=0;j<bs.length();j++){
                    String b=normalizeMac(bs.optString(j));
                    if(!b.isEmpty()&&!e.bssids.contains(b))e.bssids.add(b);
                }
                out.add(e);
            }
        }catch(Exception ignored){}
        return out;
    }
    public static void save(Context context,List<Entry> list)throws Exception{
        JSONArray arr=new JSONArray();
        for(Entry e:list){
            JSONObject x=new JSONObject();
            x.put("mac",e.mac);x.put("name",e.name);x.put("ip",e.ip);
            JSONArray b=new JSONArray();for(String id:e.bssids)b.put(id);
            x.put("bssids",b);arr.put(x);
        }
        byte[] data=arr.toString().getBytes(StandardCharsets.UTF_8);
        AtomicFile file=new AtomicFile(new File(context.getFilesDir(),FILE));
        FileOutputStream output=null;
        try {
            output=file.startWrite();output.write(data);file.finishWrite(output);
        }catch(Exception e){
            if(output!=null)file.failWrite(output);
            throw e;
        }
    }
    public static Entry lookup(List<Entry> list,String observedBssid){
        String bssid=normalizeMac(observedBssid);
        if(bssid.isEmpty())return null;
        for(Entry e:list){
            if(bssid.equals(e.mac)||e.bssids.contains(bssid))return e;
        }
        return null;
    }
    public static void link(List<Entry> list,Entry target,String observed){
        String bssid=normalizeMac(observed);
        if(bssid.isEmpty())throw new IllegalArgumentException("Enter a valid BSSID/MAC");
        for(Entry e:list)e.bssids.remove(bssid);
        if(!target.bssids.contains(bssid))target.bssids.add(bssid);
    }
    public static String readText(InputStream input,int maxBytes)throws Exception{
        if(input==null)throw new Exception("Unable to read file");
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        byte[] buf=new byte[8192];int n;
        while((n=input.read(buf))!=-1){
            if(out.size()+n>2_000_000)throw new Exception("File over 2 MB; split your AP inventory");
            out.write(buf,0,n);
        }
        return new String(out.toByteArray(),StandardCharsets.UTF_8);
    }
}
