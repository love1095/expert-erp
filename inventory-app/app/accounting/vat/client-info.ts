// What the browser tells HMRC about itself (Making Tax Digital fraud prevention headers, WEB_APP_VIA_SERVER)
export function clientInfo() {
  let deviceId = localStorage.getItem("erp_device_id");
  if (!deviceId) {
    deviceId = crypto.randomUUID();
    localStorage.setItem("erp_device_id", deviceId);
  }
  const offset = -new Date().getTimezoneOffset();
  const sign = offset >= 0 ? "+" : "-";
  const abs = Math.abs(offset);
  const timezone = `UTC${sign}${String(Math.floor(abs / 60)).padStart(2, "0")}:${String(abs % 60).padStart(2, "0")}`;
  return {
    userAgent: navigator.userAgent, deviceId, doNotTrack: navigator.doNotTrack === "1",
    screenWidth: screen.width, screenHeight: screen.height, scalingFactor: window.devicePixelRatio, colourDepth: screen.colorDepth,
    windowWidth: window.innerWidth, windowHeight: window.innerHeight, timezone,
  };
}
