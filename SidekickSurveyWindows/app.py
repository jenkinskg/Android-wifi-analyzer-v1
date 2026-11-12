import csv
import json
import os
import queue
import re
import sys
import threading
import time
import tkinter as tk
from datetime import datetime
from pathlib import Path
from tkinter import filedialog, messagebox, simpledialog, ttk
from PIL import Image, ImageTk, ImageDraw, ImageFont
from wlan_native import NativeWiFi, WLANError

HOME=Path(os.getenv("APPDATA",str(Path.home()))) / "SidekickSurveyWindows"
MAC=re.compile(r"(?i)(?<![a-f0-9])(?:(?:[a-f0-9]{2}[:-]){5}[a-f0-9]{2}|(?:[a-f0-9]{4}\.){2}[a-f0-9]{4}|[a-f0-9]{12})(?![a-f0-9])")
IP=re.compile(r"(?<!\d)(?:\d{1,3}\.){3}\d{1,3}(?!\d)")
def macnorm(s):
    s=re.sub("[^0-9a-fA-F]","",s).upper()
    return ":".join(s[i:i+2] for i in range(0,12,2)) if len(s)==12 else ""
def parse_device_list(contents):
    found={}
    for line in contents.replace("\ufeff","").splitlines():
        line=line.strip()
        if not line or line.startswith("#"):continue
        m=MAC.search(line)
        if not m:continue
        mac=macnorm(m.group())
        if not mac:continue
        ip=IP.search(line)
        address=ip.group() if ip else ""
        name=MAC.sub(" ",IP.sub(" ",line))
        name=re.sub(r'[\s,;|"\'\t]+'," ",name).strip()
        found[mac]={"device":name or mac,"ip":address,"mac":mac}
    return found

class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("Sidekick Survey Windows v1.0  |  Wi-Fi Adapter Selector")
        self.geometry("1250x820");self.minsize(960,620)
        self.adapters=[];self.adapter_var=tk.StringVar()
        self.dwell_var=tk.StringVar(value="5")
        self.near_var=tk.StringVar(value="-65")
        self.status=tk.StringVar(value="Choose Wi-Fi card, import floor plan, then tap a location to survey.")
        self.connected=tk.StringVar(value="Connected AP: unknown")
        self.measurements=[];self.devices={};self.pins={};self.hidden=set()
        self.scan_rows=[];self.floor=None;self.photo=None;self.heat=None
        self.heat_dirty=True;self.zoom=1.0;self.pan=(0,0);self.pan_anchor=None
        self.busy=False;self.cancel=threading.Event();self.events=queue.Queue()
        self._ui();self._restore()
        self.after(120,self._pump)
        self.after(220,self.refresh_adapters)
    def _ui(self):
        top=ttk.Frame(self,padding=7);top.pack(fill="x")
        ttk.Label(top,text="Sidekick Survey Windows  |  Aruba AP Survey",font=("Segoe UI",16,"bold")).pack(anchor="w")
        a=ttk.Frame(top);a.pack(fill="x",pady=4)
        ttk.Label(a,text="Wi-Fi card:").pack(side="left")
        self.combo=ttk.Combobox(a,textvariable=self.adapter_var,state="readonly",width=65)
        self.combo.pack(side="left",padx=5,fill="x",expand=True)
        ttk.Button(a,text="Refresh cards",command=self.refresh_adapters).pack(side="left",padx=2)
        ttk.Button(a,text="Scan selected card",command=self.scan_now).pack(side="left",padx=2)
        b=ttk.Frame(top);b.pack(fill="x",pady=3)
        ttk.Label(b,text="Stop & Go dwell (sec):").pack(side="left")
        ttk.Combobox(b,textvariable=self.dwell_var,values=["3","5","8","10"],state="readonly",width=4).pack(side="left",padx=3)
        ttk.Label(b,text="AP pin threshold:").pack(side="left",padx=(10,2))
        ttk.Combobox(b,textvariable=self.near_var,values=["-45","-55","-65","-75"],state="readonly",width=5).pack(side="left",padx=3)
        ttk.Button(b,text="Import JPG",command=self.import_map).pack(side="left",padx=3)
        ttk.Button(b,text="Import device,ip,mac",command=self.import_list).pack(side="left",padx=3)
        ttk.Button(b,text="Export CSV",command=self.export_csv).pack(side="left",padx=3)
        ttk.Button(b,text="Save JPG",command=self.export_jpg).pack(side="left",padx=3)
        self.cancel_btn=ttk.Button(b,text="Cancel Stop",command=self.cancel.set,state="disabled")
        self.cancel_btn.pack(side="right",padx=2)
        ttk.Label(top,textvariable=self.connected).pack(anchor="w")
        ttk.Label(top,textvariable=self.status,foreground="#20557b").pack(anchor="w",pady=3)
        self.progress=ttk.Progressbar(top,maximum=100);self.progress.pack(fill="x",pady=3)
        self.tabs=ttk.Notebook(self);self.tabs.pack(fill="both",expand=True,padx=5,pady=4)
        mapframe=ttk.Frame(self.tabs);apframe=ttk.Frame(self.tabs);deviceframe=ttk.Frame(self.tabs);pointframe=ttk.Frame(self.tabs)
        self.tabs.add(mapframe,text="Floor plan / Stop & Go")
        self.tabs.add(apframe,text="Detected APs")
        self.tabs.add(deviceframe,text="Device/IP/MAC list")
        self.tabs.add(pointframe,text="Survey points")
        mapbar=ttk.Frame(mapframe,padding=3);mapbar.pack(fill="x")
        ttk.Button(mapbar,text="Remove last point",command=self.remove_last).pack(side="left",padx=2)
        ttk.Button(mapbar,text="Clear all survey points",command=self.clear_points).pack(side="left",padx=2)
        ttk.Label(mapbar,text="Left click: record or edit • Right drag: pan • Wheel: zoom").pack(side="right")
        self.canvas=tk.Canvas(mapframe,bg="#edf3f8",highlightthickness=0)
        self.canvas.pack(fill="both",expand=True)
        self.canvas.bind("<Configure>",lambda e:self.draw_map())
        self.canvas.bind("<Button-1>",self.click_map)
        self.canvas.bind("<ButtonPress-3>",lambda e:setattr(self,"pan_anchor",(e.x,e.y)))
        self.canvas.bind("<B3-Motion>",self.move_pan)
        self.canvas.bind("<MouseWheel>",self.wheel)
        self.ap_table=self.table(apframe,["SSID","BSSID","RSSI","Channel","MHz","Device","IP"],[145,160,68,72,78,180,130])
        devicebar=ttk.Frame(deviceframe,padding=5);devicebar.pack(fill="x")
        ttk.Button(devicebar,text="Import / merge list",command=self.import_list).pack(side="left",padx=3)
        ttk.Button(devicebar,text="Remove selected device",command=self.remove_device).pack(side="left",padx=3)
        ttk.Button(devicebar,text="CLEAR entire imported list",command=self.clear_devices).pack(side="left",padx=3)
        ttk.Label(devicebar,text="MAC column is wireless BSSID").pack(side="right")
        self.device_table=self.table(deviceframe,["Device","IP","Wireless MAC/BSSID"],[240,160,220])
        pointbar=ttk.Frame(pointframe,padding=5);pointbar.pack(fill="x")
        ttk.Button(pointbar,text="Remove selected point",command=self.remove_selected).pack(side="left")
        self.point_table=self.table(pointframe,["#","Time","SSID","BSSID","Signal","Device","Dwell","Scan freshness"],[42,130,140,160,75,150,64,210])
    def table(self,parent,cols,widths):
        f=ttk.Frame(parent,padding=5);f.pack(fill="both",expand=True)
        tree=ttk.Treeview(f,columns=cols,show="headings",selectmode="browse")
        for col,width in zip(cols,widths):
            tree.heading(col,text=col);tree.column(col,width=width,minwidth=60,stretch=True)
        tree.pack(side="left",fill="both",expand=True)
        y=ttk.Scrollbar(f,orient="vertical",command=tree.yview);y.pack(side="right",fill="y")
        tree.configure(yscrollcommand=y.set)
        return tree
    def selected(self):
        n=self.combo.current()
        if n<0 or n>=len(self.adapters):
            messagebox.showwarning("Wi-Fi card","Select a Windows Wi-Fi card from the adapter drop-down.")
            return None
        return self.adapters[n]
    def job(self,fn):
        if self.busy:
            self.status.set("A measurement is in progress. Complete it or press Cancel Stop.")
            return
        self.busy=True;self.cancel.clear();self.cancel_btn.configure(state="normal")
        def run():
            try:fn()
            except Exception as exc:self.events.put(("error",str(exc)))
            finally:self.events.put(("done",None))
        threading.Thread(target=run,daemon=True).start()
    def _pump(self):
        try:
            while True:
                event,payload=self.events.get_nowait()
                if event=="adapters":
                    prior=self.adapter_var.get()
                    self.adapters=payload;self.combo.configure(values=[a.display for a in payload])
                    index=next((i for i,a in enumerate(payload) if a.display==prior),0)
                    if payload:self.combo.current(index)
                    self.status.set(str(len(payload))+" Wi-Fi cards found. Select which card to scan.")
                elif event=="scan":
                    rows,conn=payload;self.scan_rows=rows;self.show_scan();self.show_connection(conn)
                    self.status.set(str(len(rows))+" AP radios returned by the selected card.")
                elif event=="progress":
                    elapsed,total,conn=payload;self.progress["value"]=100*elapsed/total
                    self.show_connection(conn)
                    self.status.set("Stop & Go: stay still. "+str(round(max(0,total-elapsed),1))+
                                    "s left; checking the connected AP twice per second.")
                elif event=="point":
                    point,rows,conn=payload;self.scan_rows=rows
                    self.show_scan();self.show_connection(conn);self.add_point(point)
                    self.progress["value"]=100
                elif event=="cancel":
                    self.progress["value"]=0;self.status.set("Measurement canceled; no point saved.")
                elif event=="error":
                    self.status.set("Wi-Fi error: "+payload)
                    messagebox.showerror("Wi-Fi",payload)
                elif event=="done":
                    self.busy=False;self.cancel_btn.configure(state="disabled")
        except queue.Empty:pass
        self.after(120,self._pump)
    def refresh_adapters(self):
        def worker():
            try:
                with NativeWiFi() as wlan:self.events.put(("adapters",wlan.adapters()))
            except Exception as exc:self.events.put(("error",str(exc)))
        threading.Thread(target=worker,daemon=True).start()
    def scan_now(self):
        adapter=self.selected()
        if not adapter:return
        def worker():
            with NativeWiFi() as wlan:
                wlan.request_scan(adapter.guid_hex)
                time.sleep(3.0)
                self.events.put(("scan",([vars(b) for b in wlan.bss(adapter.guid_hex)],
                                         wlan.connection(adapter.guid_hex))))
        self.job(worker)
    def start_dwell(self,x,y):
        adapter=self.selected()
        if not adapter:return
        duration=int(self.dwell_var.get())
        def worker():
            with NativeWiFi() as wlan:
                baseline={}
                try:baseline={b.bssid:b.host_timestamp for b in wlan.bss(adapter.guid_hex)}
                except WLANError:pass
                error=None
                try:wlan.request_scan(adapter.guid_hex)
                except WLANError as exc:error=str(exc)
                averages={};events=[];start=time.monotonic()
                while True:
                    if self.cancel.is_set():
                        self.events.put(("cancel",None));return
                    now=time.monotonic()
                    conn=wlan.connection(adapter.guid_hex)
                    if conn.get("bssid"):
                        events.append(conn["bssid"])
                        if isinstance(conn.get("rssi"),int) and -120<=conn["rssi"]<=0:
                            averages.setdefault(conn["bssid"],[]).append(conn["rssi"])
                    self.events.put(("progress",(min(now-start,duration),duration,conn)))
                    if now-start>=duration:break
                    time.sleep(min(.5,max(0,duration-(now-start))))
                if self.cancel.is_set():
                    self.events.put(("cancel",None));return
                radios=wlan.bss(adapter.guid_hex)
                rows=[vars(r) for r in radios]
                changed=any(b.bssid not in baseline or
                            (b.host_timestamp and b.host_timestamp!=baseline.get(b.bssid))
                            for b in radios)
                connected=max(averages,key=lambda k:len(averages[k])) if averages else (events[-1] if events else "")
                values=averages.get(connected,[])
                conn=wlan.connection(adapter.guid_hex)
                if not rows and connected and values:
                    rows=[{"ssid":conn.get("ssid",""),"bssid":connected,
                           "rssi":round(sum(values)/len(values)),"frequency":0,
                           "channel":0,"quality":conn.get("quality",0),"host_timestamp":0}]
                point={"x":x,"y":y,"timestamp":datetime.now().isoformat(timespec="seconds"),
                       "adapter":adapter.description,"dwell":duration,"readings":rows,
                       "scan_changed":bool(changed),"scan_status":
                            ("scan denied" if error else
                             "BSS changed since start" if changed else
                             "scan requested; fresh results unverified"),
                       "connected_bssid":connected,
                       "connected_average":round(sum(values)/len(values),1) if values else None,
                       "connected_min":min(values) if values else None,
                       "connected_max":max(values) if values else None,
                       "connected_polls":len(values),
                       "roams":sum(events[i]!=events[i-1] for i in range(1,len(events)))}
                if self.cancel.is_set():self.events.put(("cancel",None));return
                self.events.put(("point",(point,rows,conn)))
        self.job(worker)
    def show_connection(self,conn):
        mac=conn.get("bssid","")
        if mac:
            rec=self.devices.get(mac.upper(),{})
            self.connected.set("Selected card connected to "+conn.get("ssid","")+"  "+mac+"  "+
                               rec.get("device","unmatched")+"  RSSI "+
                               str(conn.get("rssi","unknown"))+" dBm")
        else:self.connected.set("Selected card is not currently connected (scanning may still work).")
    def show_scan(self):
        self.ap_table.delete(*self.ap_table.get_children())
        for r in self.scan_rows:
            match=self.devices.get(r.get("bssid","").upper(),{})
            self.ap_table.insert("","end",values=(
                r["ssid"],r["bssid"],r["rssi"],r["channel"],r["frequency"],
                match.get("device",""),match.get("ip","")))
    def show_devices(self):
        self.device_table.delete(*self.device_table.get_children())
        for mac,r in self.devices.items():
            self.device_table.insert("","end",iid=mac,values=(r["device"],r["ip"],mac))
        self.show_scan()
    def show_points(self):
        self.point_table.delete(*self.point_table.get_children())
        for i,p in enumerate(self.measurements):
            best=max(p["readings"],key=lambda r:r["rssi"],default={})
            mac=best.get("bssid","")
            self.point_table.insert("","end",iid=str(i),values=(
                i+1,p["timestamp"],best.get("ssid",""),mac,best.get("rssi",""),
                self.devices.get(mac.upper(),{}).get("device",""),p["dwell"],p["scan_status"]))
    def import_list(self):
        path=filedialog.askopenfilename(title="Import device,ip,mac (wireless BSSID)",
                                        filetypes=[("Text/CSV","*.txt *.csv"),("All","*.*")])
        if not path:return
        try:
            with open(path,encoding="utf-8-sig",errors="replace") as f:items=parse_device_list(f.read())
            if not items:raise ValueError("No valid MAC addresses. Expected device,ip,mac.")
            self.devices.update(items);self.show_devices();self.draw_map();self.save()
            self.status.set(str(len(items))+" AP identities loaded by wireless MAC/BSSID.")
        except Exception as exc:messagebox.showerror("Import error",str(exc))
    def remove_device(self):
        item=self.device_table.selection()
        if not item:return
        mac=item[0]
        if messagebox.askyesno("Remove device","Delete "+self.devices[mac]["device"]+"  "+mac+"?"):
            self.devices.pop(mac,None);self.show_devices();self.draw_map();self.save()
    def clear_devices(self):
        if self.devices and messagebox.askyesno("Clear AP list",
             "Delete ALL "+str(len(self.devices))+" imported devices/IP/MACs?\nSurvey points and JPG are preserved."):
            self.devices.clear();self.show_devices();self.draw_map();self.save()
    def import_map(self):
        path=filedialog.askopenfilename(filetypes=[("Floor plans","*.jpg *.jpeg *.png")])
        if not path:return
        if self.measurements and not messagebox.askyesno("New map","New floor plan clears all survey points. Continue?"):
            return
        try:
            im=Image.open(path).convert("RGB");im.thumbnail((2400,2400),Image.Resampling.LANCZOS)
            HOME.mkdir(parents=True,exist_ok=True);im.save(HOME/"floor.png")
            self.floor=im;self.measurements.clear();self.pins.clear();self.hidden.clear()
            self.heat_dirty=True;self.zoom=1.;self.pan=(0,0);self.show_points();self.draw_map();self.save()
        except Exception as exc:messagebox.showerror("Map import",str(exc))
    def add_point(self,point):
        if not point["readings"]:
            self.status.set("No Wi-Fi BSSID data reported at this spot; point not saved.");return
        self.measurements.append(point)
        if point["scan_changed"]:
            placed=0
            for r in sorted(point["readings"],key=lambda row:row["rssi"],reverse=True):
                mac=r["bssid"].upper()
                if mac in self.hidden or r["rssi"]<int(self.near_var.get()):continue
                existing=self.pins.get(mac)
                if existing is None and placed<4:
                    self.pins[mac]={"x":point["x"],"y":point["y"],"peak":r["rssi"],
                                    "ssid":r["ssid"]};placed+=1
                elif existing and not existing.get("fixed") and r["rssi"]>existing["peak"]+2:
                    existing.update(x=point["x"],y=point["y"],peak=r["rssi"])
        self.heat_dirty=True;self.show_points();self.draw_map();self.save()
        best=max(point["readings"],key=lambda r:r["rssi"])
        self.status.set("Point "+str(len(self.measurements))+" saved. Strongest "+best["bssid"]+
                        " "+str(best["rssi"])+" dBm. "+point["scan_status"])
    def remove_last(self):
        if self.measurements and messagebox.askyesno("Remove last","Remove the last survey point?"):
            self.measurements.pop();self.heat_dirty=True;self.show_points();self.draw_map();self.save()
    def remove_selected(self):
        selection=self.point_table.selection()
        if not selection:return
        index=int(selection[0])
        if messagebox.askyesno("Remove point","Remove point "+str(index+1)+"?"):
            self.measurements.pop(index);self.heat_dirty=True
            self.show_points();self.draw_map();self.save()
    def clear_points(self):
        if self.measurements and messagebox.askyesno("Clear survey",
          "Remove all "+str(len(self.measurements))+" points and map pins? The device list stays."):
            self.measurements.clear();self.pins.clear();self.hidden.clear()
            self.heat_dirty=True;self.show_points();self.draw_map();self.save()
    def _transform(self):
        if not self.floor:return (0,0,1)
        w=max(self.canvas.winfo_width(),1);h=max(self.canvas.winfo_height(),1)
        scale=min(w/self.floor.width,h/self.floor.height)*self.zoom
        ox=(w-self.floor.width*scale)/2+self.pan[0]
        oy=(h-self.floor.height*scale)/2+self.pan[1]
        return ox,oy,scale
    def click_map(self,event):
        if not self.floor:return
        ox,oy,scale=self._transform()
        x=(event.x-ox)/(self.floor.width*scale)
        y=(event.y-oy)/(self.floor.height*scale)
        if not (0<=x<=1 and 0<=y<=1):return
        radius=21**2
        for mac,p in self.pins.items():
            px=ox+p["x"]*self.floor.width*scale;py=oy+p["y"]*self.floor.height*scale
            if (px-event.x)**2+(py-event.y)**2<radius:
                return self.edit_pin(mac)
        for i,p in enumerate(self.measurements):
            px=ox+p["x"]*self.floor.width*scale;py=oy+p["y"]*self.floor.height*scale
            if (px-event.x)**2+(py-event.y)**2<radius:
                return self.edit_point(i)
        if not self.busy:self.start_dwell(x,y)
    def edit_pin(self,mac):
        pin=self.pins[mac];rec=self.devices.get(mac,{})
        name=pin.get("label") or rec.get("device") or pin.get("ssid") or mac
        action=messagebox.askyesnocancel("AP pin",
             name+"\n"+mac+"\n"+rec.get("ip","")+"\n\nYES = Rename pin\nNO = Remove pin\nCANCEL = Keep")
        if action is True:
            value=simpledialog.askstring("Rename AP","Label:",initialvalue=name)
            if value is not None:pin["label"]=value;self.draw_map();self.save()
        elif action is False and messagebox.askyesno("Remove pin","Remove AP pin "+mac+"?"):
            self.pins.pop(mac,None);self.hidden.add(mac);self.draw_map();self.save()
    def edit_point(self,index):
        p=self.measurements[index]
        rows=sorted(p["readings"],key=lambda r:r["rssi"],reverse=True)
        desc="\n".join(r["bssid"]+" "+r["ssid"]+" "+str(r["rssi"])+" dBm "+
                       self.devices.get(r["bssid"].upper(),{}).get("device","") for r in rows[:9])
        if messagebox.askyesno("Survey point "+str(index+1),
             p["timestamp"]+"\n"+p["scan_status"]+
             "\nConnected RSSI avg/min/max: "+str((p["connected_average"],p["connected_min"],p["connected_max"]))+
             "\n\n"+desc+"\n\nRemove this point?"):
            self.measurements.pop(index);self.heat_dirty=True
            self.show_points();self.draw_map();self.save()
    def move_pan(self,event):
        if self.pan_anchor:
            x,y=self.pan_anchor;self.pan=(self.pan[0]+event.x-x,self.pan[1]+event.y-y)
            self.pan_anchor=(event.x,event.y);self.draw_map()
    def wheel(self,event):
        self.zoom=max(.3,min(6,self.zoom*(1.12 if event.delta>0 else 1/1.12)))
        self.draw_map()
    def _heat_overlay(self):
        if self.floor is None or not self.measurements:return None
        if not self.heat_dirty:return self.heat
        source=Image.new("RGBA",(70,70));pix=source.load()
        for y in range(70):
            for x in range(70):
                nx=(x+.5)/70;ny=(y+.5)/70;num=den=0.;nearest=99.
                for p in self.measurements:
                    if not p["readings"]:continue
                    dbm=max(r["rssi"] for r in p["readings"])
                    d2=(nx-p["x"])**2+(ny-p["y"])**2
                    nearest=min(nearest,d2**.5)
                    weight=1/(d2+.0003);num+=dbm*weight;den+=weight
                if den and nearest<.30:
                    value=max(0,min(1,(num/den+90)/55))
                    pix[x,y]=(int(230-185*value),int(55+125*value),int(40+40*value),
                              int(130*(1-nearest/.30)))
        self.heat=source.resize(self.floor.size,Image.Resampling.BILINEAR)
        self.heat_dirty=False;return self.heat
    def draw_map(self):
        if not hasattr(self,"canvas"):return
        self.canvas.delete("all")
        if not self.floor:
            self.canvas.create_text(max(200,self.canvas.winfo_width()/2),
              max(100,self.canvas.winfo_height()/2),text="Import JPG floor plan to start.",
              font=("Segoe UI",16),fill="#3e5672");return
        ox,oy,scale=self._transform()
        w=max(1,int(self.floor.width*scale));h=max(1,int(self.floor.height*scale))
        if max(w,h)>7000:return
        img=self.floor.convert("RGBA")
        heat=self._heat_overlay()
        if heat is not None:img.alpha_composite(heat)
        self.photo=ImageTk.PhotoImage(img.resize((w,h),Image.Resampling.BILINEAR))
        self.canvas.create_image(ox,oy,image=self.photo,anchor="nw")
        for i,p in enumerate(self.measurements):
            x=ox+p["x"]*w;y=oy+p["y"]*h
            best=max(p["readings"],key=lambda r:r["rssi"],default={})
            mac=best.get("bssid","")
            name=self.devices.get(mac.upper(),{}).get("device",best.get("ssid","AP"))
            self.canvas.create_oval(x-9,y-9,x+9,y+9,fill="white",outline="#1c3f59",width=2)
            self.canvas.create_text(x,y,text=str(i+1),font=("Segoe UI",8,"bold"))
            self.canvas.create_text(x+13,y+13,anchor="nw",
              text=name+" "+str(best.get("rssi",""))+" dBm\n"+mac,
              font=("Segoe UI",9),fill="#173c55")
        for mac,p in self.pins.items():
            x=ox+p["x"]*w;y=oy+p["y"]*h
            rec=self.devices.get(mac,{})
            name=p.get("label") or rec.get("device") or p.get("ssid","AP")
            self.canvas.create_oval(x-13,y-13,x+13,y+13,fill="#e59e2c",outline="#715219",width=2)
            self.canvas.create_text(x,y,text="AP",fill="white",font=("Segoe UI",8,"bold"))
            self.canvas.create_text(x+15,y-12,anchor="nw",font=("Segoe UI",9,"bold"),
              text=name+" "+rec.get("ip","")+"\n"+mac,fill="#163d56")
    def export_csv(self):
        if not self.measurements:return
        path=filedialog.asksaveasfilename(defaultextension=".csv",initialfile="sidekick-survey.csv",filetypes=[("CSV","*.csv")])
        if not path:return
        cols=["point","time","x","y","adapter","ssid","bssid","rssi","channel","frequency",
              "device","ip","scan_status","dwell","connected_bssid","connected_average",
              "connected_min","connected_max","connected_samples","roams"]
        try:
            with open(path,"w",newline="",encoding="utf-8-sig") as file:
                w=csv.DictWriter(file,fieldnames=cols);w.writeheader()
                for i,p in enumerate(self.measurements):
                    for r in p["readings"]:
                        rec=self.devices.get(r["bssid"].upper(),{})
                        w.writerow({"point":i+1,"time":p["timestamp"],"x":round(p["x"]*100,2),
                          "y":round(p["y"]*100,2),"adapter":p["adapter"],"ssid":r["ssid"],
                          "bssid":r["bssid"],"rssi":r["rssi"],"channel":r["channel"],
                          "frequency":r["frequency"],"device":rec.get("device",""),"ip":rec.get("ip",""),
                          "scan_status":p["scan_status"],"dwell":p["dwell"],
                          "connected_bssid":p["connected_bssid"],"connected_average":p["connected_average"],
                          "connected_min":p["connected_min"],"connected_max":p["connected_max"],
                          "connected_samples":p["connected_polls"],"roams":p["roams"]})
            self.status.set("CSV exported to "+path)
        except Exception as exc:messagebox.showerror("CSV export",str(exc))
    def export_jpg(self):
        if not self.floor:return
        path=filedialog.asksaveasfilename(defaultextension=".jpg",initialfile="sidekick-map.jpg",filetypes=[("JPEG","*.jpg")])
        if not path:return
        try:
            im=self.floor.convert("RGBA");heat=self._heat_overlay()
            if heat:im.alpha_composite(heat)
            draw=ImageDraw.Draw(im);font=ImageFont.load_default()
            for i,p in enumerate(self.measurements):
                x=int(p["x"]*im.width);y=int(p["y"]*im.height)
                best=max(p["readings"],key=lambda r:r["rssi"],default={})
                mac=best.get("bssid","")
                name=self.devices.get(mac.upper(),{}).get("device",best.get("ssid","AP"))
                draw.ellipse((x-7,y-7,x+7,y+7),fill="white",outline="#1c3f59",width=2)
                draw.text((x+9,y+9),str(i+1)+" "+name+" "+str(best.get("rssi",""))+" dBm\n"+mac,
                          fill="#123f5b",stroke_width=1,stroke_fill="white",font=font)
            for mac,p in self.pins.items():
                x=int(p["x"]*im.width);y=int(p["y"]*im.height)
                rec=self.devices.get(mac,{})
                draw.ellipse((x-10,y-10,x+10,y+10),fill="#e7a02b",outline="#624318",width=2)
                draw.text((x+13,y-11),(p.get("label") or rec.get("device") or p.get("ssid","AP"))+
                          " "+rec.get("ip","")+"\n"+mac,fill="#133e5b",font=font,
                          stroke_width=1,stroke_fill="white")
            im.convert("RGB").save(path,quality=94)
            self.status.set("Annotated JPG exported to "+path)
        except Exception as exc:messagebox.showerror("JPG export",str(exc))
    def save(self):
        try:
            HOME.mkdir(parents=True,exist_ok=True)
            data={"devices":self.devices,"measurements":self.measurements,"pins":self.pins,
                  "hidden":sorted(self.hidden)}
            tmp=HOME/"survey.tmp";tmp.write_text(json.dumps(data),encoding="utf-8")
            tmp.replace(HOME/"survey.json")
        except OSError as exc:self.status.set("Unable to save: "+str(exc))
    def _restore(self):
        try:
            f=HOME/"survey.json"
            if f.is_file():
                data=json.loads(f.read_text(encoding="utf-8"))
                self.devices=data.get("devices",{});self.measurements=data.get("measurements",[])
                self.pins=data.get("pins",{});self.hidden=set(data.get("hidden",[]))
            if (HOME/"floor.png").is_file():self.floor=Image.open(HOME/"floor.png").convert("RGB")
            self.show_devices();self.show_points();self.heat_dirty=True;self.draw_map()
        except Exception as exc:self.status.set("Unable to restore prior survey: "+str(exc))

if __name__=="__main__":
    if sys.platform!="win32":
        raise SystemExit("Sidekick Windows requires Windows 10 or Windows 11")
    App().mainloop()
