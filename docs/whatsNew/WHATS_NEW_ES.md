# Changelog
All notable changes to this application will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this application adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Supported section titles:
- Añadido, Cambiado, Corregido, Removido

**Please be aware that this changelog primarily focuses on user-related modifications, emphasizing changes that can
directly impact users rather than highlighting other key architectural updates.**

## [Unreleased]

## [2.8.7 (2)] - 2026-02-19

### Corregido:
- Corregido el cálculo de Enviar Todo — el destinatario ahora recibe ~96% del saldo.
- Corregido el parpadeo del campo de texto de monto personalizado.
- Corregido el sonido de notificación que no reproducía.
- Corregida la notificación de sincronización en la pantalla de bloqueo.

### Cambiado:
- Reemplazada la opción Propina Media con Enviar Todo en la selección de monto.
- Después de enviar el primer mensaje, navegar directamente a la conversación.
- Mostrar saldo disponible en la pantalla de composición y diálogo de monto.

## [2.8.6 (1)] - 2026-02-18

### Corregido:
- Corregida la deduplicación de mensajes para mensajes multi-fragmento.
- Corregida la detección de cambio pendiente para errores de fondos insuficientes.
- Corregida la seguridad de hilos del ID de conversación.
- Corregida la inconsistencia de visualización de monto cero.

## [2.8.5 (1)] - 2026-02-17

### Añadido:
- Sistema completo de notificaciones con sonido y vibración personalizados.
- Sincronización en segundo plano cada 15 minutos.
- Banners de notificación dentro de la app.
- Privacidad de notificaciones en pantalla de bloqueo.
- Pantalla de configuración de notificaciones.
- Soporte de silenciar por conversación.

## [2.8.3 (1)] - 2026-02-15

### Añadido:
- Protocolo ZMSG v4 con IDs de conversación.
- Fragmentación de mensajes para mensajes mayores a 512 bytes.
- Cifrado de extremo a extremo con intercambio de claves autenticado.
- Mensajería grupal con distribución de claves ECIES.
- Reacciones, confirmaciones de lectura y respuestas con hilo.
- Mensajes con bloqueo temporal.
- Solicitudes de pago en el chat.
- Libreta de contactos con alias.
- Tema cyberpunk con fuentes personalizadas.
