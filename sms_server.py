#!/usr/bin/env python3
"""SMS Gateway Server - Fixed: time=Beijing, slot consistent"""
import sqlite3, os, json, time, threading
from datetime import datetime, timedelta, timezone
from flask import Flask, request, jsonify, render_template_string

DB_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data")
DB_PATH = os.path.join(DB_DIR, "sms.db")
PORT = 8989
TZ = timezone(timedelta(hours=8))  # Beijing Time

os.makedirs(DB_DIR, exist_ok=True)
app = Flask(__name__)

def now():
    """Beijing time datetime"""
    return datetime.now(TZ)

def now_iso():
    """Beijing time ISO string"""
    return now().isoformat()

def get_db():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA synchronous=NORMAL")
    return conn

def init_db():
    conn = get_db()
    conn.execute("CREATE TABLE IF NOT EXISTS sms (id INTEGER PRIMARY KEY AUTOINCREMENT, phone_id TEXT NOT NULL DEFAULT 'unknown', number TEXT NOT NULL, body TEXT NOT NULL, timestamp TEXT, type TEXT DEFAULT 'received', slot INTEGER DEFAULT 0, created_at TEXT)")
    conn.execute("CREATE TABLE IF NOT EXISTS commands (id INTEGER PRIMARY KEY AUTOINCREMENT, number TEXT NOT NULL, message TEXT NOT NULL, slot INTEGER DEFAULT 0, status TEXT DEFAULT 'pending', result TEXT, created_at TEXT, executed_at TEXT)")
    try: conn.execute("ALTER TABLE commands ADD COLUMN phone_id TEXT DEFAULT 'all'")
    except: pass
    conn.execute("CREATE TABLE IF NOT EXISTS phones (phone_id TEXT PRIMARY KEY, name TEXT DEFAULT '', last_seen TEXT)")
    conn.execute("CREATE INDEX IF NOT EXISTS sms_id_desc ON sms(id DESC)")
    conn.execute("CREATE INDEX IF NOT EXISTS sms_phone ON sms(phone_id)")
    conn.execute("CREATE INDEX IF NOT EXISTS cmd_status_phone ON commands(status, phone_id)")
    conn.commit(); conn.close()

init_db()

with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "templates", "index.html")) as f:
    HTML = f.read()

@app.route("/")
def index():
    return render_template_string(HTML)

@app.route("/api/status")
def api_status():
    return jsonify({"status":"ok","time":now_iso()})

@app.route("/api/sms/receive",methods=["POST"])
def api_receive():
    d=request.json
    if not d: return jsonify({"error":"no data"}),400
    conn=get_db()
    conn.execute("INSERT INTO sms (phone_id,number,body,timestamp,type,slot,created_at) VALUES (?,?,?,?,?,?,?)",
        (d.get("phone_id","unknown"),d.get("number",""),d.get("body",""),d.get("timestamp",""),d.get("type","received"),d.get("slot",0),now_iso()))
    conn.execute("INSERT INTO phones (phone_id,last_seen) VALUES (?,?) ON CONFLICT(phone_id) DO UPDATE SET last_seen=?",(d.get("phone_id","unknown"),now_iso(),now_iso()))
    conn.commit(); conn.close()
    return jsonify({"success":True})

@app.route("/api/sms/pending")
def api_pending():
    pid=request.args.get("phone_id","unknown")
    conn=get_db()
    rows=conn.execute("SELECT * FROM commands WHERE status='pending' AND (phone_id='all' OR phone_id=?) ORDER BY id ASC LIMIT 5",(pid,)).fetchall()
    conn.close()
    return jsonify({"commands":[{"id":r["id"],"number":r["number"],"message":r["message"],"slot":r["slot"] or 0,"phone_id":r["phone_id"]} for r in rows]})

@app.route("/api/sms/done",methods=["POST"])
def api_done():
    d=request.json
    conn=get_db()
    conn.execute("UPDATE commands SET status=?,result=?,executed_at=? WHERE id=?",(d.get("status"),d.get("result",""),now_iso(),d.get("cmd_id")))
    conn.commit()
    if d.get("cmd_id"):
        cmd=conn.execute("SELECT * FROM commands WHERE id=?",(d["cmd_id"],)).fetchone()
        if cmd:
            # FIX: web sends 0-based slot, store as 1-based for display
            conn.execute("INSERT INTO sms (phone_id,number,body,timestamp,type,slot,created_at) VALUES (?,?,?,?,?,?,?)",
                (cmd["phone_id"] if cmd["phone_id"]!="all" else "server",cmd["number"],cmd["message"],now_iso(),"sent",(cmd["slot"] or 0)+1,now_iso()))
            conn.commit()
    conn.close()
    return jsonify({"success":True})

@app.route("/api/phones")
def api_phones():
    conn=get_db()
    rows=conn.execute("SELECT * FROM phones ORDER BY last_seen DESC").fetchall()
    conn.close()
    return jsonify({"phones":[{"phone_id":r["phone_id"],"name":r["name"],"last_seen":r["last_seen"]} for r in rows]})

@app.route("/api/phone/register",methods=["POST"])
def api_register():
    d=request.json;pid=d.get("phone_id","").strip();name=d.get("name","").strip()
    if not pid: return jsonify({"error":"no phone_id"}),400
    conn=get_db()
    conn.execute("INSERT INTO phones (phone_id,name) VALUES (?,?) ON CONFLICT(phone_id) DO UPDATE SET name=?,last_seen=?",(pid,name,name,now_iso()))
    conn.commit(); conn.close()
    return jsonify({"success":True})

@app.route("/api/sms/send",methods=["POST"])
def api_send():
    d=request.json;num=d.get("number","").strip();msg=d.get("message","").strip();slot=d.get("slot",0);pid=d.get("phone_id","all")
    if not num or not msg: return jsonify({"error":"缺少number或message"}),400
    conn=get_db()
    conn.execute("INSERT INTO commands (number,message,slot,phone_id,created_at) VALUES (?,?,?,?,?)",(num,msg,slot,pid,now_iso()))
    conn.commit(); conn.close()
    return jsonify({"success":True})

@app.route("/api/sms/list")
def api_list():
    page=request.args.get("page",1,type=int);pp=request.args.get("per_page",30,type=int)
    fp=request.args.get("phone_id","all");ft=request.args.get("type","all")
    off=(page-1)*pp
    conn=get_db()
    wc="";pa=[]
    if fp!="all": wc+=" AND sms.phone_id=?"; pa.append(fp)
    if ft!="all": wc+=" AND type=?"; pa.append(ft)
    total=conn.execute("SELECT COUNT(*) FROM sms WHERE 1=1"+wc,pa).fetchone()[0]
    rows=conn.execute("SELECT sms.*,phones.name as phone_name FROM sms LEFT JOIN phones ON sms.phone_id=phones.phone_id WHERE 1=1"+wc+" ORDER BY sms.id DESC LIMIT ? OFFSET ?",pa+[pp,off]).fetchall()
    conn.close()
    return jsonify({"sms":[{"id":r["id"],"phone_id":r["phone_id"],"phone_name":r["phone_name"] or "","number":r["number"],"body":r["body"],"type":r["type"],"slot":r["slot"] or 0,"created_at":r["created_at"]} for r in rows],"total":total,"page":page,"per_page":pp,"has_more":off+pp<total})

@app.route("/api/commands/list")
def api_cmd_list():
    conn=get_db()
    rows=conn.execute("SELECT * FROM commands ORDER BY id DESC LIMIT 20").fetchall()
    conn.close()
    return jsonify({"commands":[{"id":r["id"],"number":r["number"],"message":r["message"],"phone_id":r["phone_id"],"status":r["status"],"result":r["result"],"created_at":r["created_at"],"executed_at":r["executed_at"]} for r in rows]})

def cleanup():
    while True:
        try:
            time.sleep(3600)
            conn=get_db()
            cutoff=now()-timedelta(days=7)
            conn.execute("DELETE FROM sms WHERE created_at < ?",(cutoff.isoformat(),))
            conn.execute("DELETE FROM commands WHERE created_at < ? AND status!='pending'",(cutoff.isoformat(),))
            conn.commit(); conn.close()
        except: pass

t=threading.Thread(target=cleanup,daemon=True);t.start()

if __name__=="__main__":
    print(f"📱 SMS Gateway on :{PORT}")
    app.run(host="0.0.0.0",port=PORT,debug=False)
