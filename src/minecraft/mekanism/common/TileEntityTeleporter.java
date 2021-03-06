package mekanism.common;

import ic2.api.Direction;
import ic2.api.ElectricItem;
import ic2.api.IElectricItem;
import ic2.api.energy.tile.IEnergySink;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;

import universalelectricity.core.electricity.ElectricityConnections;
import universalelectricity.core.implement.IConductor;
import universalelectricity.core.implement.IItemElectric;
import universalelectricity.core.implement.IJouleStorage;
import universalelectricity.core.implement.IVoltage;
import universalelectricity.core.vector.Vector3;

import com.google.common.io.ByteArrayDataInput;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import cpw.mods.fml.server.FMLServerHandler;
import dan200.computer.api.IComputerAccess;
import dan200.computer.api.IPeripheral;

import mekanism.common.Teleporter.Coords;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.INetworkManager;
import net.minecraft.network.packet.Packet250CustomPayload;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraftforge.common.ForgeDirection;

public class TileEntityTeleporter extends TileEntityElectricBlock implements IEnergySink, IJouleStorage, IVoltage, IPeripheral
{
	/** This teleporter's frequency. */
	public Teleporter.Code code;
	
	/** This teleporter's current status. */
	public String status = (EnumColor.DARK_RED + "Not ready.");
	
	public TileEntityTeleporter()
	{
		super("Teleporter", 10000000);
		inventory = new ItemStack[1];
		code = new Teleporter.Code(0, 0, 0, 0);
		
		ElectricityConnections.registerConnector(this, EnumSet.allOf(ForgeDirection.class));
	}
	
	@Override
	public void onUpdate()
	{
		super.onUpdate();
		
		if(!worldObj.isRemote)
		{
			if(Mekanism.teleporters.containsKey(code))
			{
				if(!Mekanism.teleporters.get(code).contains(Teleporter.Coords.get(this)) && hasFrame())
				{
					Mekanism.teleporters.get(code).add(Teleporter.Coords.get(this));
				}
				else if(Mekanism.teleporters.get(code).contains(Teleporter.Coords.get(this)) && !hasFrame())
				{
					Mekanism.teleporters.get(code).remove(Teleporter.Coords.get(this));
				}
			}
			else if(hasFrame())
			{
				ArrayList<Teleporter.Coords> newCoords = new ArrayList<Teleporter.Coords>();
				newCoords.add(Teleporter.Coords.get(this));
				Mekanism.teleporters.put(code, newCoords);
			}
			
			switch(canTeleport())
			{
				case 1:
					status = EnumColor.DARK_GREEN + "Ready.";
					break;
				case 2:
					status = EnumColor.DARK_RED + "No frame.";
					break;
				case 3:
					status = EnumColor.DARK_RED + "No link found.";
					break;
				case 4:
					status = EnumColor.DARK_RED + "Links > 2.";
					break;
				case 5:
					status = EnumColor.DARK_RED + "Needs energy.";
					break;
				case 6:
					status = EnumColor.DARK_GREEN + "Idle.";
					break;
			}
		}
		
		if(powerProvider != null)
		{
			int received = (int)(powerProvider.useEnergy(0, (float)((MAX_ELECTRICITY-electricityStored)*Mekanism.TO_BC), true)*10);
			setJoules(electricityStored + received);
		}
		
		if(!worldObj.isRemote)
		{
			for(ForgeDirection direction : ForgeDirection.values())
			{
				TileEntity tileEntity = Vector3.getTileEntityFromSide(worldObj, new Vector3(this), direction);
				if(tileEntity != null)
				{
					if(tileEntity instanceof IConductor)
					{
						if(electricityStored < MAX_ELECTRICITY)
						{
							double electricityNeeded = MAX_ELECTRICITY - electricityStored;
							((IConductor)tileEntity).getNetwork().startRequesting(this, electricityNeeded, electricityNeeded >= getVoltage() ? getVoltage() : electricityNeeded);
							setJoules(electricityStored + ((IConductor)tileEntity).getNetwork().consumeElectricity(this).getWatts());
						}
						else if(electricityStored >= MAX_ELECTRICITY)
						{
							((IConductor)tileEntity).getNetwork().stopRequesting(this);
						}
					}
				}
			}
		}
		
		if(inventory[0] != null)
		{
			if(electricityStored < MAX_ELECTRICITY)
			{
				if(inventory[0].getItem() instanceof IItemElectric)
				{
					IItemElectric electricItem = (IItemElectric)inventory[0].getItem();

					if (electricItem.canProduceElectricity())
					{
						double joulesNeeded = MAX_ELECTRICITY-electricityStored;
						double joulesReceived = electricItem.onUse(Math.min(electricItem.getMaxJoules(inventory[0])*0.005, joulesNeeded), inventory[0]);
						setJoules(electricityStored + joulesReceived);
					}
				}
				else if(inventory[0].getItem() instanceof IElectricItem)
				{
					IElectricItem item = (IElectricItem)inventory[0].getItem();
					if(item.canProvideEnergy())
					{
						double gain = ElectricItem.discharge(inventory[0], (int)((MAX_ELECTRICITY - electricityStored)*Mekanism.TO_IC2), 3, false, false)*Mekanism.FROM_IC2;
						setJoules(electricityStored + gain);
					}
				}
			}
			if(inventory[0].itemID == Item.redstone.itemID && electricityStored+1000 <= MAX_ELECTRICITY)
			{
				setJoules(electricityStored + 1000);
				--inventory[0].stackSize;
				
	            if (inventory[0].stackSize <= 0)
	            {
	                inventory[0] = null;
	            }
			}
		}
	}
	
	/**
	 * 1: yes
	 * 2: no frame
	 * 3: no link found
	 * 4: too many links
	 * 5: not enough electricity
	 * 6: nothing to teleport
	 * @return
	 */
	public byte canTeleport()
	{
		if(!hasFrame())
		{
			return 2;
		}
		
		if(!Mekanism.teleporters.containsKey(code) || Mekanism.teleporters.get(code).isEmpty())
		{
			return 3;
		}
		
		if(Mekanism.teleporters.get(code).size() > 2) 
		{
			return 4;
		}
		
		if(Mekanism.teleporters.get(code).size() == 2)
		{
			List<EntityPlayer> entitiesInPortal = worldObj.getEntitiesWithinAABB(EntityPlayer.class, AxisAlignedBB.getBoundingBox(xCoord-1, yCoord, zCoord-1, xCoord+1, yCoord+3, zCoord+1));

			Teleporter.Coords closestCoords = null;
			
			for(Teleporter.Coords coords : Mekanism.teleporters.get(code))
			{
				if(!coords.equals(Teleporter.Coords.get(this)))
				{
					closestCoords = coords;
					break;
				}
			}
			
			int electricityNeeded = 0;
			
			for(EntityPlayer entity : entitiesInPortal)
			{
				electricityNeeded += calculateEnergyCost(entity, closestCoords);
			}
			
			if(entitiesInPortal.size() == 0)
			{
				return 6;
			}
			
			if(electricityStored < electricityNeeded)
			{
				return 5;
			}
			
			return 1;
		}
		
		return 3;
	}
	
	public void teleport()
	{
		if(worldObj.isRemote) return;
		
		List<EntityPlayer> entitiesInPortal = worldObj.getEntitiesWithinAABB(EntityPlayer.class, AxisAlignedBB.getBoundingBox(xCoord-1, yCoord, zCoord-1, xCoord+1, yCoord+3, zCoord+1));

		Teleporter.Coords closestCoords = null;
		
		for(Teleporter.Coords coords : Mekanism.teleporters.get(code))
		{
			if(!coords.equals(Teleporter.Coords.get(this)))
			{
				closestCoords = coords;
				break;
			}
		}
		
		for(EntityPlayer entity : entitiesInPortal)
		{
			setJoules(electricityStored - calculateEnergyCost(entity, closestCoords));
			
			worldObj.playSoundAtEntity((EntityPlayerMP)entity, "mob.endermen.portal", 1.0F, 1.0F);
			
			if(entity.worldObj.provider.dimensionId != closestCoords.dimensionId)
			{
				entity.travelToDimension(closestCoords.dimensionId);
			}
			
			((EntityPlayerMP)entity).playerNetServerHandler.setPlayerLocation(closestCoords.xCoord+0.5, closestCoords.yCoord, closestCoords.zCoord+0.5, entity.rotationYaw, entity.rotationPitch);
			
			for(Teleporter.Coords coords : Mekanism.teleporters.get(code))
			{
				PacketHandler.sendPortalFX(coords.xCoord, coords.yCoord, coords.zCoord, coords.dimensionId);
			}
		}
	}
	
	@Override
	public void invalidate()
	{
		super.invalidate();
		
		if(!worldObj.isRemote)
		{
			if(Mekanism.teleporters.containsKey(code))
			{
				if(Mekanism.teleporters.get(code).contains(Teleporter.Coords.get(this)))
				{
					Mekanism.teleporters.get(code).remove(Teleporter.Coords.get(this));
				}
				
				if(Mekanism.teleporters.get(code).isEmpty()) Mekanism.teleporters.remove(code);
			}
		}
	}
	
	public int calculateEnergyCost(Entity entity, Teleporter.Coords coords)
	{
		int energyCost = 1000;
		
		if(entity.worldObj.provider.dimensionId != coords.dimensionId)
		{
			energyCost+=10000;
		}
		
		int distance = (int)entity.getDistance(coords.xCoord, coords.yCoord, coords.zCoord);
		System.out.println(distance);
		energyCost+=distance;
		
		return energyCost;
	}
	
	public boolean hasFrame()
	{
		if(isFrame(xCoord-1, yCoord, zCoord) && isFrame(xCoord+1, yCoord, zCoord)
				&& isFrame(xCoord-1, yCoord+1, zCoord) && isFrame(xCoord+1, yCoord+1, zCoord)
				&& isFrame(xCoord-1, yCoord+2, zCoord) && isFrame(xCoord+1, yCoord+2, zCoord)
				&& isFrame(xCoord-1, yCoord+3, zCoord) && isFrame(xCoord+1, yCoord+3, zCoord)
				&& isFrame(xCoord, yCoord+3, zCoord)) {return true;}
		if(isFrame(xCoord, yCoord, zCoord-1) && isFrame(xCoord, yCoord, zCoord+1)
				&& isFrame(xCoord, yCoord+1, zCoord-1) && isFrame(xCoord, yCoord+1, zCoord+1)
				&& isFrame(xCoord, yCoord+2, zCoord-1) && isFrame(xCoord, yCoord+2, zCoord+1)
				&& isFrame(xCoord, yCoord+3, zCoord-1) && isFrame(xCoord, yCoord+3, zCoord+1)
				&& isFrame(xCoord, yCoord+3, zCoord)) {return true;}
		return false;
	}
	
	public boolean isFrame(int x, int y, int z)
	{
		return worldObj.getBlockId(x, y, z) == Mekanism.basicBlockID && worldObj.getBlockMetadata(x, y, z) == 8;
	}
	
	public int getScaledEnergyLevel(int i)
	{
		return (int)(electricityStored*i / MAX_ELECTRICITY);
	}
	
	@Override
    public void readFromNBT(NBTTagCompound nbtTags)
    {
        super.readFromNBT(nbtTags);

        code.digitOne = nbtTags.getInteger("digitOne");
        code.digitTwo = nbtTags.getInteger("digitTwo");
        code.digitThree = nbtTags.getInteger("digitThree");
        code.digitFour = nbtTags.getInteger("digitFour");
    }

	@Override
    public void writeToNBT(NBTTagCompound nbtTags)
    {
        super.writeToNBT(nbtTags);
        
        nbtTags.setInteger("digitOne", code.digitOne);
        nbtTags.setInteger("digitTwo", code.digitTwo);
        nbtTags.setInteger("digitThree", code.digitThree);
        nbtTags.setInteger("digitFour", code.digitFour);
    }
	
	@Override
	public void handlePacketData(ByteArrayDataInput dataStream)
	{
		if(!worldObj.isRemote)
		{
			if(Mekanism.teleporters.containsKey(code))
			{
				if(Mekanism.teleporters.get(code).contains(Teleporter.Coords.get(this)))
				{
					Mekanism.teleporters.get(code).remove(Teleporter.Coords.get(this));
				}
				
				if(Mekanism.teleporters.get(code).isEmpty()) Mekanism.teleporters.remove(code);
			}
			
			int type = dataStream.readInt();
			
			if(type == 0)
			{
				code.digitOne = dataStream.readInt();
			}
			else if(type == 1)
			{
				code.digitTwo = dataStream.readInt();
			}
			else if(type == 2)
			{
				code.digitThree = dataStream.readInt();
			}
			else if(type == 3)
			{
				code.digitFour = dataStream.readInt();
			}
			return;
		}
		
		super.handlePacketData(dataStream);
		status = dataStream.readUTF().trim();
		code.digitOne = dataStream.readInt();
		code.digitTwo = dataStream.readInt();
		code.digitThree = dataStream.readInt();
		code.digitFour = dataStream.readInt();
	}
	
	@Override
	public ArrayList getNetworkedData(ArrayList data)
	{
		super.getNetworkedData(data);
		data.add(status);
		data.add(code.digitOne);
		data.add(code.digitTwo);
		data.add(code.digitThree);
		data.add(code.digitFour);
		return data;
	}

	@Override
	public boolean acceptsEnergyFrom(TileEntity emitter, Direction direction)
	{
		return true;
	}

	@Override
	public String getType()
	{
		return getInvName();
	}

	@Override
	public String[] getMethodNames()
	{
		return new String[] {"getStored", "canTeleport", "getMaxEnergy", "getEnergyNeeded", "teleport", "set"};
	}

	@Override
	public Object[] callMethod(IComputerAccess computer, int method, Object[] arguments) throws Exception
	{
		switch(method)
		{
			case 0:
				return new Object[] {electricityStored};
			case 1:
				return new Object[] {canTeleport()};
			case 2:
				return new Object[] {MAX_ELECTRICITY};
			case 3:
				return new Object[] {(MAX_ELECTRICITY-electricityStored)};
			case 4:
				teleport();
				return new Object[] {"Attempted to teleport."};
			case 5:
				if(!(arguments[0] instanceof Integer) || !(arguments[1] instanceof Integer))
				{
					return new Object[] {"Invalid parameters."};
				}
				
				int digit = (Integer)arguments[0];
				int newDigit = (Integer)arguments[1];
				
				switch(digit)
				{
					case 0:
						code.digitOne = newDigit;
						break;
					case 1:
						code.digitTwo = newDigit;
						break;
					case 2:
						code.digitThree = newDigit;
						break;
					case 3:
						code.digitFour = newDigit;
						break;
					default:
						return new Object[] {"No digit found."};
				}
			default:
				System.err.println("[Mekanism] Attempted to call unknown method with computer ID " + computer.getID());
				return new Object[] {"Unknown command."};
		}
	}

	@Override
	public boolean canAttachToSide(int side)
	{
		return true;
	}

	@Override
	public void attach(IComputerAccess computer) {}

	@Override
	public void detach(IComputerAccess computer) {}

	@Override
	public double getVoltage(Object... data)
	{
		return 120;
	}

	@Override
	public double getJoules(Object... data)
	{
		return electricityStored;
	}

	@Override
	public void setJoules(double joules, Object... data)
	{
		electricityStored = Math.max(Math.min(joules, getMaxJoules()), 0);
	}

	@Override
	public double getMaxJoules(Object... data)
	{
		return MAX_ELECTRICITY;
	}

	@Override
	public int demandsEnergy()
	{
		return (int)((MAX_ELECTRICITY - electricityStored)*Mekanism.TO_IC2);
	}

	@Override
	public int injectEnergy(Direction directionFrom, int amount)
	{
		double givenEnergy = amount*Mekanism.FROM_IC2;
    	double rejects = 0;
    	double neededEnergy = MAX_ELECTRICITY-electricityStored;
    	
    	if(givenEnergy < neededEnergy)
    	{
    		electricityStored += givenEnergy;
    	}
    	else if(givenEnergy > neededEnergy)
    	{
    		electricityStored += neededEnergy;
    		rejects = givenEnergy-neededEnergy;
    	}
    	
    	return (int)(rejects*Mekanism.TO_IC2);
	}

	@Override
	public int getMaxSafeInput()
	{
		return 2048;
	}
}
