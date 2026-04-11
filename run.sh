echo '#!/bin/bash
java --module-path lib \
     --add-modules javafx.controls,javafx.media \
     -cp out \
     com.player.App' > run.sh
chmod +x run.sh