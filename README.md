# V-APK

Aplicativo Android em Java 17 com HUD flutuante, analisador de arquivos Java-like e agente neural treinado por aprendizado por reforço.

## Fluxo do arquivo

1. Toque em **Abrir arquivo** e selecione um arquivo `.java`, `.txt` ou `.vapk`.
2. O V-APK lê o conteúdo, analisa toda a estrutura aceita e valida cada comando antes de executar qualquer um.
3. Depois da análise, a IA fica em **modo de espera**.
4. Toque em **LIGAR IA + EXECUTAR** na janela HUD.
5. O executor emite o evento interno `SCRIPT_STARTED` imediatamente antes do primeiro comando. Nesse momento, a IA muda para ativa e o plano validado é executado na HUD.
6. Ao terminar, a IA volta ao modo de espera.

A HUD é uma sobreposição Android arrastável. O sistema pedirá a permissão **Exibir sobre outros apps**.

## Formato Java aceito

O arquivo pode usar uma classe e um método `run()`. O V-APK interpreta somente os comandos da API HUD abaixo; ele não compila nem executa bytecode Java arbitrário.

```java
public class Example {
    public static void run() {
        HUD.setTitle("V-APK / Java");
        HUD.setText("Agente neural pronto.");
        HUD.log("Script iniciado");
        HUD.setColor("#0D1627");
        HUD.waitMs(250);
        HUD.log("Comandos finalizados");
        HUD.setVisible(true);
    }
}
```

Comandos permitidos:

- `HUD.setTitle("texto")`
- `HUD.setText("texto")`
- `HUD.log("texto")`
- `HUD.setColor("#RRGGBB")`
- `HUD.setVisible(true | false)`
- `HUD.waitMs(0..1000)`

Limites: 100 KB por arquivo, 100 comandos e 5 segundos de espera acumulada. Qualquer instrução fora dessa API faz o arquivo inteiro ser recusado antes da execução.

O executor atual modifica apenas a HUD do V-APK. Ele não injeta comandos em outros aplicativos nem executa operações arbitrárias no Android. Para controlar outro aplicativo/jogo, será necessária uma integração específica e autorizada.

## Rede neural e treinamento

A rede é um Deep Q-Network com quatro entradas, quatro camadas ocultas de 100 neurônios e cinco saídas de ação:

`4 → 100 → 100 → 100 → 100 → 5`

O agente treina numa arena de combate simulada contra um inimigo. As observações incluem distância, vida do jogador, vida do inimigo e nível de perigo. As ações incluem aproximar, recuar, atacar, esquivar e defender. O treinamento usa recompensas/penalidades, replay buffer, atualização temporal-difference e uma rede-alvo. No primeiro início, o app treina 120 episódios; é possível repetir o treinamento pelo botão **Treinar IA**.

O modelo é salvo no armazenamento privado do aplicativo como `model_4x100.bin`. A avaliação final usa 100 partidas simuladas.

## Compilação

O GitHub Actions compila o APK de depuração a cada push na branch `main` e publica o artefato `V-APK-debug`.

Requisitos para compilação manual: JDK 17, Android SDK Platform 35, Build Tools 35.0.0 e Gradle 8.9.

```bash
gradle --no-daemon :app:assembleDebug
```

Saída: `app/build/outputs/apk/debug/app-debug.apk`.
