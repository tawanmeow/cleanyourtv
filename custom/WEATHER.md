# Home status weather

Home shows the current weather symbol and rounded Celsius temperature immediately left of Wi-Fi, separated by a bullet: `☁ 28°C • [Wi-Fi] [Settings]`. The date is uppercase English with a weekday and two-digit year: `MON • 2 NOV 26`. Both remain visible in Idle Mode.

The area is Lat Sawai, Lam Luk Ka. The approximate area coordinate is configured in `launcher/app/src/main/res/values/weather.xml` (13.93264, 100.68903); this is not a device GPS location. Change those two resource strings to select another area.

Weather data: [Open-Meteo](https://open-meteo.com/) ([API documentation](https://open-meteo.com/en/docs)), distributed under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Current values are weather-model estimates, not readings from a sensor attached to the TV. The implementation requests current temperature, WMO weather code and day/night status, and rounds the temperature to an integer.

The launcher now requires Android's normal INTERNET permission. It uses HTTPS with the platform certificate checks and requests no new runtime/location permission. No API key or additional library is required.

Refresh occurs every 15 minutes while Home is resumed, including Idle Mode. Failed requests retry after 5 minutes. Cached readings older than 2 hours are hidden together with their separator. Network work runs on a worker thread, stops scheduling when Home is hidden, and is released with the Activity. Weather uses the existing date font size, color and shadow, and adds no focus targets or remote actions. The status icons stay anchored to the same right edge.

This code change does not update the installed app until a build/install is explicitly requested. The distribution APK and its checksums continue to describe the previous installed build until then.
