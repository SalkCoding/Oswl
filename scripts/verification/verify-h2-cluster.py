"""Two owned JVMs and a disposable H2 DB. No deployment or external notifications."""
import argparse, copy, http.cookiejar, json, os, re, socket, subprocess, tempfile, time, urllib.request, urllib.parse, urllib.error
from pathlib import Path
from cluster_assertions import session_identity, session_rejected, scheduler_cycles

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args): return None

def port():
    with socket.socket() as value:
        value.bind(("127.0.0.1",0));return value.getsockname()[1]

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument("--jar",required=True);args=parser.parse_args()
    root=Path(__file__).resolve().parents[2];jar=Path(args.jar).resolve()
    if not jar.is_file(): raise ValueError("Build a production bootJar first")
    work=Path(tempfile.mkdtemp(prefix="h2-cluster-",dir=root/"build"));print(work,flush=True)
    h2=next((Path.home()/".gradle/caches/modules-2/files-2.1/com.h2database/h2").rglob("h2-*.jar"))
    db="jdbc:h2:file:"+(work/"cluster").as_posix()+";MODE=PostgreSQL;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=30000;WRITE_DELAY=0"
    flags=dict(creationflags=subprocess.CREATE_NO_WINDOW) if os.name=="nt" else {}
    subprocess.run(["java","-cp",str(h2),"org.h2.tools.RunScript","-url",db,"-user","sa","-password","","-script",str(root/"src/main/resources/db/spring_session_and_shedlock.sql")],check=True,**flags)
    processes=[];handles=[];ports=[port(),port()];logs={};results={}
    common=["--server.address=127.0.0.1","--spring.profiles.active=cluster-verification","--spring.datasource.url="+db,"--spring.datasource.driver-class-name=org.h2.Driver","--spring.datasource.username=sa","--spring.datasource.password=","--spring.session.store-type=jdbc","--oswl.scheduler-lock.enabled=true","--oswl.monitoring.enabled=true","--oswl.monitoring.cron=0/20 * * * * *","--oswl.encryption.key=KOaEB3zxojumnrmUsXpl4tPQGXSCLd+pE5bEuOirJy0=","--oswl.airgapped.enabled=true","--spring.jpa.open-in-view=false"]
    def request(index,path,cookies=None,data=None):
        opener=urllib.request.build_opener(NoRedirect(),urllib.request.HTTPCookieProcessor(cookies if cookies is not None else http.cookiejar.CookieJar()))
        req=urllib.request.Request(f"http://127.0.0.1:{ports[index]}"+path,data=urllib.parse.urlencode(data).encode() if data else None)
        try: response=opener.open(req,timeout=10)
        except urllib.error.HTTPError as error: response=error
        with response:return response.status,response.read().decode("utf-8")
    def csrf(body):
        match=re.search(r'name="_csrf"[^>]*value="([^"]+)"',body) or re.search(r'name="_csrf"[^>]*content="([^"]+)"',body)
        if not match:raise AssertionError("Missing CSRF token")
        return match[1]
    def sql(command):
        result=subprocess.run(["java","-cp",str(h2),"org.h2.tools.Shell","-url",db,"-user","sa","-password","","-sql",command],capture_output=True,text=True,check=True,**flags)
        if "Error:" in result.stdout:raise AssertionError("Fixture SQL failed")
        return result.stdout
    def start(index,ddl):
        label=str(index)+"-"+str(len(processes));log=work/("instance-"+label+".log");logs[label]=log
        stream=log.open("w",encoding="utf-8");handles.append(stream)
        process=subprocess.Popen(["java","-Xmx768m","-jar",str(jar),"--server.port="+str(ports[index]),"--spring.jpa.hibernate.ddl-auto="+ddl,"--oswl.ai.embedded.dir="+str(work/("ai-"+str(index)))]+common,stdout=stream,stderr=subprocess.STDOUT,**flags);processes.append(process)
        for _ in range(90):
            if process.poll() is not None:raise AssertionError("JVM startup failed: "+log.name)
            try:
                if request(index,"/setup")[0] in (200,302):return process
            except (OSError,urllib.error.URLError):pass
            time.sleep(1)
        raise AssertionError("JVM readiness timeout")
    try:
        a=start(0,"update");b=start(1,"none");cookies=http.cookiejar.CookieJar();email="cluster@example.test";password="ClusterVerifyPass123!"
        _,body=request(0,"/setup",cookies)
        status,_=request(0,"/setup",cookies,dict(email=email,password=password,passwordConfirm=password,displayName="Cluster Verification",_csrf=csrf(body)));assert status==302,"Setup failed"
        def login(index,jar):
            _,body=request(index,"/login",jar)
            status,_=request(index,"/login",jar,dict(email=email,password=password,_csrf=csrf(body)));assert status==302,"Login failed"
        login(0,cookies)
        status,body=request(1,"/onboarding",cookies);assert session_identity(status,body,email),"Cross-instance session failed"
        results["crossInstanceSession"]=True
        assert session_rejected(request(1,"/onboarding")[0]);results["anonymousNegativeControl"]=True
        stale=http.cookiejar.CookieJar()
        for cookie in cookies:stale.set_cookie(copy.copy(cookie))
        status,_=request(0,"/logout",cookies,dict(_csrf=csrf(body)));assert status==302
        assert session_rejected(request(1,"/onboarding",stale)[0]),"Logged-out cookie accepted";results["logoutOldCookieRejected"]=True
        cookies=http.cookiejar.CookieJar();login(1,cookies)
        status,body=request(1,"/onboarding",cookies)
        assert session_identity(status,body,email),"New login on B failed before kill, status="+str(status)
        for number,canceled in [(1,False),(2,True)]:
            job="00000000-0000-0000-0000-00000000000"+str(number)
            snapshot=json.dumps(dict(jobId=job,phase="CLONING",componentCount=0))
            sql("INSERT INTO import_jobs(job_id,owner_id,repo_key,worker_id,phase,snapshot_json,lease_until,created_at,canceled,worker_active) VALUES('"+job+"',1,'fixture-"+str(number)+"','departed-worker','CLONING','"+snapshot+"',DATEADD('SECOND',10,CURRENT_TIMESTAMP),CURRENT_TIMESTAMP,"+str(canceled).upper()+",TRUE)")
        a.kill();a.wait(timeout=20)
        status,body=request(1,"/onboarding",cookies)
        assert session_identity(status,body,email),"Surviving JVM lost session, status="+str(status);results["survivingJvmSession"]=True
        a=start(0,"none");assert session_identity(*request(0,"/onboarding",cookies),email),"Restarted JVM lost session";results["restartedJvmSession"]=True
        second=http.cookiejar.CookieJar();login(0,second)
        assert session_rejected(request(1,"/onboarding",cookies)[0]),"Single-session limit not shared";results["singleSessionAcrossInstances"]=True
        for _ in range(30):
            proof=sql("SELECT CASE WHEN COUNT(*)=2 AND SUM(CASE WHEN phase='FAILED' AND finished_at IS NOT NULL AND worker_active=FALSE THEN 1 ELSE 0 END)=2 AND SUM(CASE WHEN canceled THEN 1 ELSE 0 END)=1 THEN 'LEASE_RECOVERED' ELSE 'WAIT' END FROM import_jobs WHERE worker_id='departed-worker'")
            if "LEASE_RECOVERED" in proof:break
            time.sleep(1)
        else:raise AssertionError("Expired fixture jobs were not recovered")
        assert "NO_DUPLICATE_SCAN" in sql("SELECT CASE WHEN COUNT(*)=0 THEN 'NO_DUPLICATE_SCAN' ELSE 'UNEXPECTED_SCAN' END FROM scan_results")
        results["expiredFixtureJobsTerminatedWithoutReplay"]=True
        results["fixtureCancellationPreserved"]=True
        time.sleep(65)
        cycles=scheduler_cycles({key:path.read_text(encoding="utf-8") for key,path in logs.items()})
        ordered=sorted(cycles);results["observedSchedulerCycles"]=len(cycles);results["missingCyclesBetweenObservations"]=[cycle for cycle in range(ordered[0],ordered[-1]+1) if cycle not in cycles]
        results["oneOwnerPerObservedCycle"]=True;results["database"]="H2 PostgreSQL mode, same host; not PostgreSQL or LB evidence"
        (work/"result.json").write_text(json.dumps(results,indent=2),encoding="utf-8");print(json.dumps(results),flush=True)
    finally:
        for process in processes:
            if process.poll() is None:process.terminate()
        for process in processes:
            try:process.wait(timeout=15)
            except subprocess.TimeoutExpired:process.kill();process.wait(timeout=10)
        for stream in handles:stream.close()

if __name__=="__main__":main()
