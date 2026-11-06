# PG Control — Widgets Edition

Esta rama parte de `pg-launcher-banner-editor` y conserva el editor de banners integrado en PG Launcher.

## Cambios de PG Control
- Control gradual del tamaño global de las tarjetas (75%–150%), manteniendo intactos los banners.
- Catálogo inicial de widgets: reloj, fecha, hora y fecha, información del TV Box, red local y bienvenida.
- Vista previa 16:9 para seleccionar y arrastrar widgets a cualquier zona de la pantalla.
- Ajuste independiente de ancho, alto y estilo visual (Cristal, Cinemático, Sólido y Contorno).
- Hasta 12 widgets configurables y guardados en el TV Box.

## Integración
- `app`: launcher Android TV, conserva el editor de banners y renderiza los widgets.
- `pgcontrol`: aplicación móvil para configurar tarjetas y widgets.
- `PgControlServer`: servidor LAN autenticado en TCP 8765.

## API nueva
- `GET /v1/launcher/widgets`
- `POST /v1/launcher/widgets`
- `GET /v1/launcher/card-scale`
- `POST /v1/launcher/card-scale`

La conexión requiere que el celular y el TV Box estén en la misma red local. El servidor no debe exponerse a Internet.

## Compilación
El workflow **Build PG Leanback test APK** genera:
- APK de prueba de PG Launcher con sufijo de paquete `.bannereditor`, para instalarlo en paralelo con el launcher estable.
- APK de PG Control para el celular.

La instalación y el funcionamiento en el TV Box real siguen requiriendo prueba física.