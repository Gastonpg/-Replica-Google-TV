# PG Control — Réplica Google TV

Esta rama experimental integra PG Control Agent directamente en la réplica Google TV PG.

## Componentes
- `app`: launcher de TV existente, conservando el diseño y la configuración visual del proyecto.
- `pgcontrol`: aplicación Android para el celular.
- `PgControlServer`: servidor HTTP local integrado en el launcher, puerto TCP 8765.

## Emparejamiento
1. Instalar y abrir la APK de la réplica Google TV PG en el TV Box.
2. Abrir el menú de configuración desde el logo PG y tocar **Conectar PG Control (celular)**.
3. Copiar la dirección local y el token que muestra el televisor.
4. Instalar PG Control en el celular, introducir dirección y token y tocar Conectar.
5. Ambos dispositivos deben estar en la misma red local.

El token se genera una vez y queda guardado en el TV Box. El servidor exige el token Bearer en todas las rutas salvo el chequeo básico `GET /v1/health`. No configurar reenvío de puertos del router ni exponer el puerto 8765 a Internet.

## Funciones
- Detección de aplicaciones iniciables del sistema (incluye actividades Leanback).
- Cambiar nombre visible, ocultar, ordenar y asignar categoría personalizada.
- Clasificación automática básica en Streaming, Música, Juegos, Herramientas y Otros.
- Icono personalizado, con acción independiente **Aplicar solo el icono**.
- Tamaño, estilo y radio de esquinas de tarjetas.
- Distribución: fila horizontal (predeterminada), cuadrícula, categorías en filas o categorías en cuadrícula.
- Cambios sincronizados con el launcher mediante broadcast local.

## API local
- `GET /v1/health`
- `GET /v1/device`
- `GET /v1/packages`
- `GET /v1/launcher/app-configs`
- `POST /v1/launcher/app-config`
- `GET /v1/launcher/layout`
- `POST /v1/launcher/layout`

## Compilación
GitHub Actions compila ambos APKs en el workflow **Build Réplica Google TV** y publica el artefacto `Replica-Google-TV-PG-Control` con:
- `app-debug.apk` — launcher TV.
- `pgcontrol-debug.apk` — app de celular.

Esta rama es experimental; no modifica `main`. La prueba física en el TV Box y el celular sigue siendo necesaria.
