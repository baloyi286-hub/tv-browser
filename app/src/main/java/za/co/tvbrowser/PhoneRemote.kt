package za.co.tvbrowser

import android.content.Context
import android.net.wifi.WifiManager
import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address
import java.net.NetworkInterface

class PhoneRemote(private val onAction: (String, Int, Int, String) -> Unit) : NanoHTTPD(8765) {
    val pairingToken = java.util.UUID.randomUUID().toString().replace("-", "")
    fun address(): String? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        while (interfaces.hasMoreElements()) {
            val network = interfaces.nextElement()
            if (!network.isUp || network.isLoopback) continue
            val ips = network.inetAddresses
            while (ips.hasMoreElements()) {
                val ip = ips.nextElement()
                if (ip is Inet4Address && ip.isSiteLocalAddress) return "http://${ip.hostAddress}:8765/?token=$pairingToken"
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
            if (action in setOf("move", "click", "back", "home", "scroll", "text", "play")) onAction(action, x, y, value)
            return newFixedLengthResponse("OK")
        }
        val html = """
          <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
          <style>body{background:#101b22;color:white;font-family:sans-serif;margin:20px}button,input{font-size:20px;padding:14px;border-radius:12px;margin:5px}#pad{height:55vh;background:#263c4b;border-radius:20px;touch-action:none;display:grid;place-items:center}</style></head>
          <body><h2>TV Browser Remote</h2><div><button onclick="send('back')">Back</button><button onclick="send('home')">Home</button><button onclick="send('play')">Play video</button></div>
          <div id="pad">Move finger to control TV cursor<br>Tap to click</div>
          <input id="txt" placeholder="Type on TV"><button onclick="send('text',0,0,document.getElementById('txt').value)">Send text</button>
          <div><button onclick="send('scroll',0,-100)">Scroll up</button><button onclick="send('scroll',0,100)">Scroll down</button></div>
          <script>
          const token=new URLSearchParams(location.search).get('token');
          function send(action,x=0,y=0,value=''){fetch('/control?'+new URLSearchParams({token,action,x,y,value})).catch(()=>{});}
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
