package folk.sisby.surveyor.mixin;

import folk.sisby.surveyor.SurveyorWorld;
import folk.sisby.surveyor.landmark.PlayerDeathLandmark;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayerEntity.class)
public class MixinServerPlayerEntity {
    @Inject(method = "onDeath", at = @At("TAIL"))
    public void onDeath(DamageSource damageSource, CallbackInfo ci) {
        ServerPlayerEntity self = (ServerPlayerEntity) (Object) this;
        ((SurveyorWorld) self.getServerWorld()).surveyor$getWorldSummary().landmarks().put(
            self.getServerWorld(),
            new PlayerDeathLandmark(self.getBlockPos(), self.getUuid(), self.getDamageTracker().getDeathMessage(), self.getServerWorld().getTimeOfDay(), self.getRandom().nextInt())
        );
    }
}
