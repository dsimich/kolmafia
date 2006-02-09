/**
 * Copyright (c) 2005, KoLmafia development team
 * http://kolmafia.sourceforge.net/
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 *  [1] Redistributions of source code must retain the above copyright
 *      notice, this list of conditions and the following disclaimer.
 *  [2] Redistributions in binary form must reproduce the above copyright
 *      notice, this list of conditions and the following disclaimer in
 *      the documentation and/or other materials provided with the
 *      distribution.
 *  [3] Neither the name "KoLmafia development team" nor the names of
 *      its contributors may be used to endorse or promote products
 *      derived from this software without specific prior written
 *      permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 * FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
 * ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package net.sourceforge.kolmafia;

import java.io.File;
import java.io.FileInputStream;

/**
 * An auxiliary class which stores runnable adventures so that they
 * can be created directly from a database.  Encapsulates the nature
 * of the adventure so that they can be easily listed inside of a
 * <code>ListModel</code>, with the potential to be extended to fit
 * other requests to the Kingdom of Loathing which need to be stored
 * within a database.
 */

public class KoLAdventure implements Runnable, KoLConstants, Comparable
{
	private static final AdventureResult WAND = new AdventureResult( 626, 1 );
	public static final AdventureResult BEATEN_UP = new AdventureResult( "Beaten Up", 1 );

	protected KoLmafia client;
	private String zone, adventureID, formSource, adventureName;
	private KoLRequest request;

	/**
	 * Constructs a new <code>KoLAdventure</code> with the given
	 * specifications.
	 *
	 * @param	client	The client to which the results of the adventure are reported
	 * @param	formSource	The form associated with the given adventure
	 * @param	adventureID	The identifier for this adventure, relative to its form
	 * @param	adventureName	The string form, or name of this adventure
	 */

	public KoLAdventure( KoLmafia client, String zone, String formSource, String adventureID, String adventureName )
	{
		this.client = client;
		this.zone = zone;
		this.formSource = formSource;
		this.adventureID = adventureID;
		this.adventureName = adventureName;

		if ( formSource.equals( "sewer.php" ) )
			this.request = new SewerRequest( client, false );
		else if ( formSource.equals( "luckysewer.php" ) )
			this.request = new SewerRequest( client, true );
		else if ( formSource.equals( "campground.php" ) )
			this.request = new CampgroundRequest( client, adventureID );
		else
			this.request = new AdventureRequest( client, formSource, adventureID );
	}

	/**
	 * Returns the form source for this adventure.
	 */

	public String getFormSource()
	{	return formSource;
	}

	/**
	 * Returns the name of this adventure.
	 */

	public String getAdventureName()
	{	return adventureName;
	}

	/**
	 * Returns the zone in which this adventure is found.
	 * @return	The zone for this adventure
	 */

	public String getZone()
	{	return zone;
	}

	/**
	 * Returns the adventure ID for this adventure.
	 * @return	The adventure ID for this adventure
	 */

	public String getAdventureID()
	{	return adventureID;
	}

	/**
	 * Returns the request associated with this adventure.
	 * @return	The request for this adventure
	 */

	public KoLRequest getRequest()
	{	return request;
	}

	/**
	 * Retrieves the string form of the adventure contained within this
	 * encapsulation, which is generally the name of the adventure.
	 *
	 * @return	The string form of the adventure
	 */

	public String toString()
	{
		if ( client == null )
			return adventureName;

		boolean includeZoneName = client.getSettings().getProperty( "showAdventureZone" ).equals( "true" );
		return includeZoneName ? zone + ": " + adventureName : adventureName;
	}

	/**
	 * Executes the appropriate <code>KoLRequest</code> for the adventure
	 * encapsulated by this <code>KoLAdventure</code>.
	 */

	public void run()
	{
		String action = StaticEntity.getProperty( "battleAction" );

		if ( ( action.equals( "item0536" ) && FightRequest.DICTIONARY1.getCount( KoLCharacter.getInventory() ) < 1 ) ||
			 ( action.equals( "item1316" ) && FightRequest.DICTIONARY2.getCount( KoLCharacter.getInventory() ) < 1 ) )
		{
			client.updateDisplay( ERROR_STATE, "Sorry, you don't have a dictionary." );
			return;
		}

		// Before running the request, make sure you have enough
		// mana and health to continue.

		if ( !formSource.equals( "campground.php" ) )
		{
			String scriptPath = StaticEntity.getProperty( "betweenBattleScript" );
			if ( !scriptPath.equals( "" ) )
				DEFAULT_SHELL.executeLine( scriptPath );

			if ( !client.recoverHP() || !client.recoverMP() )
				return;
		}

		int haltTolerance = (int)( Double.parseDouble( StaticEntity.getProperty( "battleStop" ) ) * (double) KoLCharacter.getMaximumHP() );
		if ( haltTolerance != 0 && KoLCharacter.getCurrentHP() <= haltTolerance )
		{
			client.updateDisplay( ABORT_STATE, "Insufficient health to continue (auto-abort triggered)." );
			client.cancelRequest();
			return;
		}

		// If auto-recovery failed, return from the run attempt.
		// This prevents other messages from overriding the actual
		// error message.

		if ( !client.permitsContinue() )
			return;

		// Make sure there are enough adventures to run the request
		// so that you don't spam the server unnecessarily.

		if ( KoLCharacter.getAdventuresLeft() == 0 || KoLCharacter.getAdventuresLeft() < request.getAdventuresUsed() )
		{
			client.cancelRequest();
			client.updateDisplay( ERROR_STATE, "Insufficient adventures to continue." );
			return;
		}

		// Check for dictionaries as a battle strategy, if the
		// person is not adventuring at the chasm.

		if ( !adventureID.equals( "80" ) && request instanceof AdventureRequest &&
			request.getAdventuresUsed() == 1 && (action.equals( "item0536" ) || action.equals( "item1316" )) &&
			KoLCharacter.getFamiliar().getID() != 16 && KoLCharacter.getFamiliar().getID() != 17 && KoLCharacter.getFamiliar().getID() != 48 )
		{
			client.cancelRequest();
			client.updateDisplay( ERROR_STATE, "A dictionary would be useless there." );
			return;
		}

		// If you're fighting the naughty sorceress, be sure to
		// equip the wand first.

		if ( formSource.equals( "lair6.php" ) )
		{
			if ( !KoLCharacter.getEquipment( KoLCharacter.WEAPON ).startsWith( "wand" ) )
			{
				AdventureDatabase.retrieveItem( WAND );
				if ( !client.permitsContinue() )
					return;

				(new EquipmentRequest( client, WAND.getName() )).run();
			}
		}

		// If the test is successful, then it is safe to run the
		// request (without spamming the server).

		request.run();
		client.registerAdventure( this );

		// Once the request is complete, be sure to deduct the
		// used adventures from the tally

		int adventures = getAdventuresUsed();
		if ( adventures > 0 )
			client.processResult( new AdventureResult( AdventureResult.ADV, 0 - adventures ) );

		// After running the request, make sure you have enough
		// mana and health to continue so you don't get an abort
		// when the user has a good script already there.

		if ( !formSource.equals( "campground.php" ) )
		{
			if ( !client.permitsContinue() )
			{
				client.recoverHP();
				client.recoverMP();
			}
		}

		if ( haltTolerance != 0 && KoLCharacter.getCurrentHP() <= haltTolerance )
		{
			client.updateDisplay( ABORT_STATE, "Insufficient health to continue (auto-abort triggered)." );
			client.cancelRequest();
			return;
		}
	}

	/**
	 * An alternative method to doing adventure calculation is determining
	 * how many adventures are used by the given request, and subtract
	 * them after the request is done.  This number defaults to <code>zero</code>;
	 * overriding classes should change this value to the appropriate
	 * amount.
	 *
	 * @return	The number of adventures used by this request.
	 */

	public int getAdventuresUsed()
	{	return request.getAdventuresUsed();
	}

	public int compareTo( Object o )
	{
		return ( o == null || !( o instanceof KoLAdventure ) ) ? 1 :
			compareTo( (KoLAdventure) o );
	}

	public int compareTo( KoLAdventure ka )
	{	return toString().compareToIgnoreCase( ka.toString() );
	}
}
