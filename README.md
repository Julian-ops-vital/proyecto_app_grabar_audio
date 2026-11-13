# Grabador de Audio por Palabra Secreta con Widget (Android - Kotlin)

Proyecto desarrollado para la materia **Ingeniería en Dispositivos Móviles** (Semestre 7).

---

## 🎯 Objetivo y Requisitos Cumplidos

Esta aplicación en **Kotlin** permite la activación de grabación de audio por voz y mediante un Widget para la pantalla de inicio:

1. **Activación y Parada por Palabras Clave (Hotwords):**
   - **Inicio de Grabación:** Al pronunciar la palabra clave configurada (por defecto `"grabar"` o `"iniciar"`), la aplicación comienza a grabar audio automáticamente.
   - **Detención de Grabación:** Al pronunciar la palabra clave de detención (por defecto `"detener"` o `"parar"`), la aplicación concluye la grabación y guarda el archivo.
   - **Personalización:** Ambas palabras clave pueden cambiarse en cualquier momento desde la interfaz.

2. **Almacenamiento y Reproducción de Audios:**
   - **Dentro de la Aplicación:** Cuenta con un reproductor integrado con barra de desplazamiento (SeekBar), control de Play/Pausa, tiempo transcurrido, compartición directa y eliminación.
   - **Desde los Archivos del Celular:** Los audios se guardan en formato estándar **WAV (PCM 16-bit, 16kHz mono)** en la carpeta pública del dispositivo (`Recordings/AudiosSecretos` o `Music/AudiosSecretos`) y quedan indexados automáticamente mediante `MediaStore`, permitiendo abrirlos con aplicaciones del sistema como *Google Files*, *Mis Archivos de Samsung*, *VLC*, WhatsApp, etc.

3. **Widget para la Pantalla de Inicio:**
   - Dispone de un **AppWidget** interactivo que se puede colocar en la pantalla de inicio.
   - Al tocar el widget se inicia la grabación de audio de inmediato; si ya está grabando, se detiene y guarda el archivo con un segundo toque.
   - Cambia su aspecto visual en tiempo real (icono y texto en rojo indicando *"Grabando..."* y estado de reposo *"Toca para Grabar"*).

---

## 🏗️ Arquitectura Técnica del Proyecto

```
proyecto_app_grabar_audio/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml             # Permisos, Foreground Service, AppWidget y FileProvider
│   │   ├── java/com/example/audiosecretrecorder/
│   │   │   ├── MainActivity.kt             # Interfaz principal, permisos y reproductor
│   │   │   ├── model/
│   │   │   │   └── RecordingModel.kt       # Modelo de datos de cada archivo de audio
│   │   │   ├── service/
│   │   │   │   ├── AudioRecordService.kt   # Foreground Service que gestiona la grabación en segundo plano
│   │   │   │   └── KeywordManager.kt       # Motor de detección de palabras clave
│   │   │   ├── util/
│   │   │   │   └── WavHelper.kt            # Escritura de cabeceras RIFF/WAV e inserción en MediaStore
│   │   │   └── widget/
│   │   │       └── AudioRecordWidget.kt    # Proveedor del Widget de escritorio (AppWidgetProvider)
│   │   └── res/
│   │       ├── layout/                     # activity_main.xml, widget_audio_recorder.xml, item_recording.xml
│   │       ├── drawable/                   # Iconos vectoriales y fondos redondeados
│   │       ├── values/                     # strings.xml, colors.xml, themes.xml
│   │       └── xml/                        # audio_record_widget_info.xml, file_paths.xml
├── build.gradle.kts                        # Configuración raíz
└── settings.gradle.kts                     # Repositorios y módulos
```

### ¿Cómo se resolvió el conflicto de micrófono en Android?
En Android, el micrófono (`AudioSource.MIC`) suele ser un recurso de captura exclusiva. Intentar ejecutar el reconocedor de voz nativo al mismo tiempo que una grabadora tradicional suele provocar colisiones (`ERROR_AUDIO`).
Para solucionar esto de manera profesional:
- **AudioRecord:** Se utiliza un flujo directo de buffers PCM con `AudioRecord` que escribe los datos directamente al archivo `.wav`.
- **Doble Motor:**
  1. **Modo Secuencial Inteligente (Nativo):** Utiliza `SpeechRecognizer` de Android en bucle continuo para esperar la palabra de inicio; en cuanto la detecta, transiciona de forma fluida a la captura de audio en alta fidelidad.
  2. **Motor Offline Vosk:** La arquitectura incluye soporte para recibir el buffer PCM de `AudioRecord` en paralelo para detectar la palabra de detención sin ninguna colisión de hardware.

---

## 🚀 Pasos para Abrir y Ejecutar el Proyecto en Android Studio

1. Abre **Android Studio**.
2. Selecciona **Open** (Abrir) y navega a la carpeta de este proyecto:
   ```
   c:\Users\vital\OneDrive\Escritorio\Semestre 7\Ingenieria en dispositivos moviles\proyecto_app_grabar_audio
   ```
3. Espera a que Gradle sincronice las dependencias del proyecto.
4. Conecta tu teléfono Android mediante depuración USB (o inicia un emulador con micrófono habilitado).
5. Haz clic en el botón verde **Run (Ejecutar)**.

---

## 📱 Cómo Usar la Aplicación

### 1. Conceder Permisos
Al abrir la app por primera vez, se solicitarán los permisos de **Micrófono** y **Notificaciones** (en Android 13+). Presiona *Permitir*.

### 2. Configurar Palabras Clave
- En la sección **Configuración de Palabras Clave**, puedes personalizar la palabra para iniciar (ej. `grabar`) y la palabra para detener (ej. `detener`).
- Presiona **Guardar Palabras**.

### 3. Activar la Detección por Voz
- Presiona el botón verde **"Activar Escucha por Voz"**.
- El icono del micrófono se pondrá en verde y el estado dirá *"Escuchando palabra clave..."*.
- Di en voz alta: **"grabar"**.
- El icono cambiará a rojo, comenzará a palpitar, el cronómetro empezará a correr y dirá *"¡GRABANDO AUDIO!"*.
- Di en voz alta: **"detener"** (o presiona detener en la app/notificación/widget).
- La grabación finalizará y aparecerá instantáneamente en la lista inferior con su duración y peso.

### 4. Cómo Agregar y Usar el Widget en la Pantalla de Inicio
1. Ve a la pantalla de inicio de tu celular.
2. Mantén presionado un espacio vacío en la pantalla.
3. Toca en **Widgets**.
4. Busca la aplicación **"Grabador por Palabra Secreta"**.
5. Arrastra el widget **"Grabador Secreto"** a tu pantalla de inicio.
6. ¡Listo! Al tocar el botón del widget, la grabación comenzará al instante sin necesidad de abrir la aplicación. Al tocarlo nuevamente, la grabación se detendrá y se guardará.

### 5. Cómo Escuchar los Audios
- **En la App:** En la lista de audios, presiona el botón **Play**. Aparecerá una barra inferior con la SeekBar para avanzar o retroceder el audio a tu gusto.
- **En el Celular:** Abre la aplicación **Archivos**, **Mis Archivos** o **Google Files** de tu celular. Ve a la carpeta `Recordings/AudiosSecretos` (o `Music/AudiosSecretos`); allí encontrarás todos los archivos `.wav` listos para reproducir con cualquier reproductor.

