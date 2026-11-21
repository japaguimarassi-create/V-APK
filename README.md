# V-APK 2.0

Aplicativo Android nativo em Java 17, reconstruído com HUD flutuante, automação entre aplicativos via Android Accessibility Service e rede neural 4×100 com aprendizado por reforço.

## O que foi reconstruído

- Interface nativa Android com editor, importação e exportação de arquivos.
- HUD flutuante arrastável.
- Serviço de acessibilidade separado, ativado manualmente pelo usuário nas configurações do Android.
- Script Java-like com lista fechada de comandos e limite de tamanho, quantidade de instruções e tempo de espera.
- Rede neural com arquitetura 4 → 100 → 100 → 100 → 100 → 5.
- Arena de inimigos simulados, recompensas, penalizações, replay buffer, exploração epsilon-greedy, rede-alvo e Double DQN.
- Salvamento local do modelo treinado.

## Usar em outros aplicativos

1. Abra o V-APK.
2. Toque em **Ativar HUD** e conceda a permissão de sobreposição.
3. Toque em **Acessibilidade**.
4. Nas configurações do Android, entre em **Serviços instalados** e ative **V-APK Automation**.
5. Abra o aplicativo que deseja automatizar.
6. Volte ao V-APK, carregue ou escreva um script e toque em **Executar script**.

O serviço opera sobre a interface atualmente visível e somente após a autorização explícita do usuário. Ele não injeta código em processos de terceiros, não lê nem transmite o conteúdo de eventos e não preenche campos de senha. Apps podem impedir automação em telas seguras, conteúdo protegido, interfaces desenhadas em canvas ou componentes que não exponham semântica de acessibilidade. Portanto, não há garantia de compatibilidade com todos os aplicativos.

## Comandos disponíveis

Cada comando deve ocupar uma linha. Um wrapper simples de classe/método Java é aceito, mas o app executa somente as instruções documentadas abaixo; não compila bytecode Java arbitrário nem interpreta chamadas de APIs livres.

```java
public class Example {
    public static void run() {
        HUD.setTitle("V-APK");
        HUD.setText("Automação pronta");
        HUD.log("Começando");
        HUD.setColor("#102030");

        APP.tapText("Continuar");
        APP.typeText("Texto de teste");
        APP.scroll("forward");
        APP.scroll("backward");
        APP.back();

        APP.waitMs(300);
        HUD.setVisible(true);
    }
}
```

- `HUD.setTitle("texto")`: altera o título da HUD.
- `HUD.setText("texto")`: substitui o texto da HUD.
- `HUD.log("texto")`: adiciona uma mensagem à HUD.
- `HUD.setColor("#RRGGBB")`: altera a cor do painel.
- `HUD.setVisible(true)`: mostra a HUD; `false` a oculta.
- `APP.tapText("texto exato")`: tenta clicar no elemento visível com esse texto ou descrição.
- `APP.typeText("texto")`: define o texto no campo editável atualmente focado. Campos de senha são recusados.
- `APP.scroll("forward")` ou `APP.scroll("backward")`: rola a primeira área rolável da janela ativa.
- `APP.back()`: executa a ação Voltar do Android.
- `APP.waitMs(300)`: pausa o script; o total de espera não pode exceder 30 segundos.

Limites: 100 KB por arquivo, 250 comandos por execução, 5 segundos por pausa e 30 segundos somados de espera. A automação só começa quando o usuário toca em **Executar script** e pode ser cancelada no app.

## Rede neural

A política recebe quatro valores normalizados: distância do inimigo, vida do agente, vida do inimigo e indicador de perigo imediato. Ela escolhe entre aproximar, recuar, atacar, bloquear e esquivar.

O treinamento inclui:
- exploração epsilon-greedy;
- replay buffer de 4.000 transições;
- mini-lotes de 16 experiências;
- desconto temporal `gamma = 0.97`;
- taxa de aprendizado `0.0002`;
- rede-alvo atualizada periodicamente;
- cálculo Double DQN do valor da próxima ação;
- recompensas por causar dano e vencer, penalidades por errar ataques e sofrer dano;
- checkpoint do melhor modelo avaliado.

O modelo é treinado na arena simulada incorporada ao app. Os resultados da arena não comprovam habilidade em jogos comerciais nem em cenários externos. O arquivo de pesos fica no armazenamento privado do V-APK.

## Compilar

Requisitos: JDK 17, Android SDK Platform 35, Android Build Tools 35.0.0 e Gradle 8.9.

```bash
gradle clean :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

O GitHub Actions compila em cada push na branch `main` e publica o APK de debug como artefato da execução.
