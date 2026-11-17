# IFPI Parnaíba — Android WebView

Aplicativo Android WebView para `https://soumoiss.github.io/ifpi/`, desenvolvido em Java, com AndroidX Core para compartilhamento seguro de arquivos.

**Recursos**: navegação HTTP/HTTPS e `target="_blank"` dentro do aplicativo, permissões de câmera/microfone e localização solicitadas sob demanda, seletor nativo de arquivos e câmera, downloads para a pasta Downloads, suporte a arquivos `blob:` de até 16 MiB, tela de erro com botão Recarregar, vídeo em tela cheia, cookies e armazenamento local.

## Compilar localmente
Instale JDK 17 e o Android SDK 36. No diretório `android` execute `gradle :app:assembleDebug` (Gradle 8.13). O APK assinado para desenvolvimento estará em `android/app/build/outputs/apk/debug/app-debug.apk`.

## Compilar pelo GitHub Actions
O workflow `.github/workflows/build-android.yml` cria um APK de desenvolvimento assinado, envia um artefato e tenta publicar um asset na release `ifpi-webview-v1`.

**Limitações**: serviços web podem bloquear autenticação em WebView; DRM e integrações nativas específicas podem exigir SDKs próprios; downloads `blob:` maiores que 16 MiB não são aceitos pela ponte nativa; arquivos e câmera dependem das permissões concedidas e de apps instalados. Um APK não conserta páginas 404 do site. Instalações futuras de APKs assinados por chaves diferentes podem exigir remoção da versão anterior.
