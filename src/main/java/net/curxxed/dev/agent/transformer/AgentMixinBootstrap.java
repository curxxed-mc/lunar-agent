package net.curxxed.dev.agent.transformer;

import net.curxxed.dev.agent.AgentBootstrap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Lunar fallback path: the direct Minecraft transformer handles vanilla/Badlion
// and usually Lunar too, but this keeps the existing Ichor/Mixin bootstrap route alive.
@Mixin(targets = "net.minecraft.client.Minecraft", remap = false)
public class AgentMixinBootstrap {

    @Inject(method = "startGame", at = @At("RETURN"), remap = false, require = 0)
    private void agentBootstrap(CallbackInfo ci) {
        AgentBootstrap.bootstrapLoadedMods(this.getClass().getClassLoader());
    }
}
