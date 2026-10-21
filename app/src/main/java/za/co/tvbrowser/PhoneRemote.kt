package za.co.tvbrowser

import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address
import java.net.NetworkInterface

class PhoneRemote(private val onAction: (String, Int, Int, String) -> Unit) : NanoHTTPD(0) {
    private fun localIp(): String? {
        for (name in listOf("wlan0", "eth0")) {
            val net = NetworkInterface.getByName(name) ?: continue
            if (!net.isUp) continue
            val addresses = net.inetAddresses
            while (addresses.hasMoreElements()) {
                val ip = addresses.nextElement()
                if (ip is Inet4Address && ip.isSiteLocalAddress) return ip.hostAddress
            }
        }
        return null
    }
    val pairingToken = java.util.UUID.randomUUID().toString().replace("-", "")
    fun address(): String? {
        val port = listeningPort
        if (port <= 0) return null
        val preferred = localIp()
        if (preferred != null) return "http://$preferred:$port/?token=$pairingToken"
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        while (interfaces.hasMoreElements()) {
            val network = interfaces.nextElement()
            if (!network.isUp || network.isLoopback || network.isVirtual) continue
            val ips = network.inetAddresses
            while (ips.hasMoreElements()) {
                val ip = ips.nextElement()
                if (ip is Inet4Address && ip.isSiteLocalAddress)
                    return "http://${ip.hostAddress}:$port/?token=$pairingToken"
            }
        }
        return null
    }
    override fun serve(session: IHTTPSession): Response {
        val token = session.parameters["token"]?.firstOrNull()
        if (token != pairingToken) return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Pair using the TV QR code")
        if (session.uri == "/control") {
            val action = session.parameters["action"]?.firstOrNull().orEmpty()
            val x = session.parameters["x"]?.firstOrNull()?.toIntOrNull()?.coerceIn(-200, 200) ?: 0
            val y = session.parameters["y"]?.firstOrNull()?.toIntOrNull()?.coerceIn(-200, 200) ?: 0
            val value = session.parameters["value"]?.firstOrNull().orEmpty().take(2000)
            if (action in setOf("move", "click", "back", "home", "scroll", "text", "go", "play")) onAction(action, x, y, value)
            return newFixedLengthResponse("OK")
        }
        val html = """
          <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1,viewport-fit=cover">
          <title>NOVA Remote</title>
          <style>
          :root{color-scheme:dark;--mint:#76e8b3;--muted:#a5b8c5}
          *{box-sizing:border-box}body{margin:0;min-height:100dvh;background:radial-gradient(ellipse at 75% 0%,#234c50 0%,#132a3a 40%,#0b1524 100%);color:#f2fafc;font-family:system-ui,-apple-system,sans-serif;padding:calc(env(safe-area-inset-top) + 22px) 18px 24px}
          header{display:flex;justify-content:space-between;align-items:center;margin:0 0 26px}header b{font-size:24px;letter-spacing:.12em;color:var(--mint)}header span{font-size:12px;color:var(--muted);background:#284652;padding:9px 13px;border-radius:99px}
          .controls{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin-bottom:18px}
          button{border:0;background:#294153;color:#f4fafb;border-radius:22px;padding:15px 8px;font-size:15px;font-weight:600;box-shadow:0 7px 18px #0003;touch-action:manipulation}
          button:active{background:#356f67;transform:scale(.97)}
          #pad{height:48dvh;min-height:240px;border-radius:32px;background:linear-gradient(145deg,#2c5260,#182d43);box-shadow:inset 0 1px #ffffff18,0 20px 40px #0004;touch-action:none;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:12px;user-select:none}
          .cursor{width:45px;height:45px;border-radius:50%;background:#76e8b324;display:grid;place-items:center;color:var(--mint);font-size:28px}
          small{color:var(--muted)}.search{display:flex;gap:8px;margin:18px 0}
          input{flex:1;min-width:0;border:0;outline:none;border-radius:20px;background:#273d51;color:white;padding:15px;font-size:16px}
          .search button{background:var(--mint);color:#0d2527;padding:14px 18px}
          .scroll{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-top:10px}
          </style></head>
          <body><header><b>NOVA</b><span>● TV CONNECTED</span></header>
          <div class="controls"><button onclick="send('back')">← Back</button><button onclick="send('home')">⌂ Home</button><button onclick="send('play')">▶ Play</button></div>
          <div id="pad"><div class="cursor">⌁</div><strong>Touchpad</strong><small>Move to navigate · Tap to select</small></div>
          <div class="search"><input id="txt" placeholder="Search or enter URL" enterkeyhint="go"><button onclick="navigate()">Open →</button></div>
          <div class="scroll"><button onclick="send('scroll',0,-100)">↑ Scroll up</button><button onclick="send('scroll',0,100)">↓ Scroll down</button></div>
          <script>
          const token=new URLSearchParams(location.search).get('token');
          function send(action,x=0,y=0,value=''){fetch('/control?'+new URLSearchParams({token,action,x,y,value})).catch(()=>{});}
          function navigate(){send('go',0,0,document.getElementById('txt').value);document.activeElement.blur()}
          document.getElementById('txt').addEventListener('keydown',e=>{if(e.key==='Enter')navigate()});
          const pad=document.getElementById('pad');let px=0,py=0,moved=false;
          pad.onpointerdown=e=>{px=e.clientX;py=e.clientY;moved=false;pad.setPointerCapture(e.pointerId)};
          pad.onpointermove=e=>{if(!pad.hasPointerCapture(e.pointerId))return;let x=e.clientX-px,y=e.clientY-py;if(Math.abs(x)+Math.abs(y)>2){moved=true;send('move',Math.round(x*1.5),Math.round(y*1.5));px=e.clientX;py=e.clientY}};
          pad.onpointerup=e=>{if(!moved)send('click')};
          </script></body></html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("X-Frame-Options", "DENY")
        }
    }
}
