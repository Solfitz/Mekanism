package mekanism.common;

import net.minecraft.entity.Entity;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EntityDamageSourceIndirect;

public class DamageSourceMekanism extends EntityDamageSourceIndirect
{
    private Entity damageSourceProjectile;
    private Entity damageSourceEntity;

    public DamageSourceMekanism(String s, Entity entity, Entity entity1)
    {
        super(s, entity, entity1);
        damageSourceProjectile = entity;
        damageSourceEntity = entity1;
    }

    public Entity getProjectile()
    {
        return damageSourceProjectile;
    }

    @Override
    public Entity getEntity()
    {
        return damageSourceEntity;
    }

    public static DamageSource causeWeaponDamage(Entity entity, Entity entity1)
    {
        return (new DamageSourceMekanism("weapon", entity, entity1)).setProjectile();
    }
}
