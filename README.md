This app will read a GPX track file and send out an ADSB signal to replay the saved flight track. In addition to the standard lat/lon/altitude tags in a standard GPX, it can also handle additional extension tags for traffic and weather. You can see the file structure in example.gpx. This enables a more realistic playback include traffic and weather. This was designed to work with the ADSBMonitor app, which will save all incoming ADSB packets into a GPX format.



The app has been tested with Avare and AvareX, but in theory it should work with any EFB. On Avare, you need to set the GPS source to "Avare Apps Only" to avoid conflict with your internal GPS. On AvareX, the GPS source should be set to "External".

