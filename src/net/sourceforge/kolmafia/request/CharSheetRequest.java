package net.sourceforge.kolmafia.request;

import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import net.sourceforge.kolmafia.AscensionClass;
import net.sourceforge.kolmafia.AscensionPath.Path;
import net.sourceforge.kolmafia.KoLCharacter;
import net.sourceforge.kolmafia.KoLConstants;
import net.sourceforge.kolmafia.KoLmafia;
import net.sourceforge.kolmafia.moods.HPRestoreItemList;
import net.sourceforge.kolmafia.objectpool.EffectPool;
import net.sourceforge.kolmafia.objectpool.SkillPool;
import net.sourceforge.kolmafia.persistence.SkillDatabase;
import net.sourceforge.kolmafia.preferences.Preferences;
import net.sourceforge.kolmafia.session.GreyYouManager;
import net.sourceforge.kolmafia.session.ResultProcessor;
import net.sourceforge.kolmafia.session.YouRobotManager;
import net.sourceforge.kolmafia.utilities.HTMLParserUtils;
import net.sourceforge.kolmafia.utilities.StringUtilities;
import org.htmlcleaner.DomSerializer;
import org.htmlcleaner.HtmlCleaner;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class CharSheetRequest extends GenericRequest {
  private static final Pattern BASE_PATTERN = Pattern.compile(" \\(base: ([\\d,]+)\\)");

  private static final HtmlCleaner cleaner = HTMLParserUtils.configureDefaultParser();
  private static final DomSerializer domSerializer = new DomSerializer(cleaner.getProperties());

  /**
   * Constructs a new <code>CharSheetRequest</code>. The data in the KoLCharacter entity will be
   * overridden over the course of this request.
   */
  public CharSheetRequest() {
    // The only thing to do is to retrieve the page from
    // the- all variable initialization comes from
    // when the request is actually run.

    super("charsheet.php");
  }

  /**
   * Runs the request. Note that only the character's statistics are retrieved via this retrieval.
   */
  @Override
  protected boolean retryOnTimeout() {
    return true;
  }

  @Override
  public String getHashField() {
    return null;
  }

  @Override
  public void run() {
    KoLmafia.updateDisplay("Retrieving character data...");
    super.run();
  }

  @Override
  public void processResults() {
    CharSheetRequest.parseStatus(this.responseText);
  }

  public static void parseStatus(final String responseText) {
    // Currently, this is used only for parsing the list of skills
    Document doc;
    try {
      doc = domSerializer.createDOM(cleaner.clean(responseText));
    } catch (ParserConfigurationException e) {
      e.printStackTrace();
      return;
    }

    // Strip all of the HTML from the server reply
    // and then figure out what to do from there.

    var pos = 0;
    var tokens = responseText.replaceAll("><", "").replaceAll("<.*?>", "\n").split("\n");

    while (!tokens[pos].startsWith(" (#")) {
      pos++;
    }

    var id = tokens[pos];
    KoLCharacter.setUserId(StringUtilities.parseInt(id.substring(3, id.length() - 1)));
    pos++;

    String className = tokens[++pos].trim();

    // Hit point parsing begins with the first index of
    // the words indicating that the upcoming token will
    // show the HP values (Current, Maximum).

    while (!tokens[pos].startsWith("Current")) {
      pos++;
    }

    long currentHP = StringUtilities.parseLong(tokens[++pos]);
    while (!tokens[pos].startsWith("Maximum")) {
      pos++;
    }

    long maximumHP = StringUtilities.parseLong(tokens[++pos]);
    KoLCharacter.setHP(
        currentHP, maximumHP, CharSheetRequest.retrieveBase(tokens[++pos], maximumHP));

    // Allow a null class to fall through to the default case
    var ascensionClass = KoLCharacter.getAscensionClass();
    switch (ascensionClass == null ? AscensionClass.SEAL_CLUBBER : ascensionClass) {
      case ZOMBIE_MASTER:
        // Zombie Masters have a Horde.
        while (!tokens[pos].startsWith("Zombie Horde")) {
          pos++;
        }

        int horde = StringUtilities.parseInt(tokens[++pos]);
        KoLCharacter.setMP(horde, horde, horde);
        break;
      case VAMPYRE:
        // Vampyres have no MP
        break;
      default:
        // Mana point parsing is exactly the same as hit point
        // parsing - so this is just a copy-paste of the code.

        while (!tokens[pos].startsWith("Current")) {
          pos++;
        }

        long currentMP = StringUtilities.parseLong(tokens[++pos]);
        while (!tokens[pos].startsWith("Maximum")) {
          pos++;
        }

        long maximumMP = StringUtilities.parseLong(tokens[++pos]);
        KoLCharacter.setMP(
            currentMP, maximumMP, CharSheetRequest.retrieveBase(tokens[++pos], maximumMP));
        break;
    }

    // Players with a custom title will have their actual class shown in this area.

    while (!tokens[pos].startsWith("Mus")) {
      if (tokens[pos].equals("Class:")) {
        className = tokens[++pos].trim();
        break;
      }
      pos++;
    }

    // Set the ascension class that we've seen
    KoLCharacter.setAscensionClass(className);

    // Next, you begin parsing the different stat points;
    // this involves hunting for the stat point's name,
    // skipping the appropriate number of tokens, and then
    // reading in the numbers.

    long[] mus = CharSheetRequest.findStatPoints(tokens, pos, "Mus");
    pos = (int) mus[2];
    long[] mys = CharSheetRequest.findStatPoints(tokens, pos, "Mys");
    pos = (int) mys[2];
    long[] mox = CharSheetRequest.findStatPoints(tokens, pos, "Mox");
    pos = (int) mox[2];

    KoLCharacter.setStatPoints((int) mus[0], mus[1], (int) mys[0], mys[1], (int) mox[0], mox[1]);

    // Drunkenness may or may not exist (in other words,
    // if the character is not drunk, nothing will show
    // up). Therefore, parse it if it exists; otherwise,
    // parse until the "Adventures remaining:" token.

    while (!tokens[pos].startsWith("Temul")
        && !tokens[pos].startsWith("Inebr")
        && !tokens[pos].startsWith("Tipsi")
        && !tokens[pos].startsWith("Drunk")
        && !tokens[pos].startsWith("Adven")) {
      pos++;
    }

    if (!tokens[pos].startsWith("Adven")) {
      KoLCharacter.setInebriety(StringUtilities.parseInt(tokens[++pos]));
      while (!tokens[pos].startsWith("Adven")) {
        pos++;
      }
    } else {
      KoLCharacter.setInebriety(0);
    }

    // Now parse the number of adventures remaining,
    // the monetary value in the character's pocket,
    // and the number of turns accumulated.

    int oldAdventures = KoLCharacter.getAdventuresLeft();
    int newAdventures = StringUtilities.parseInt(tokens[++pos]);
    ResultProcessor.processAdventuresLeft(newAdventures - oldAdventures);

    while (!tokens[pos].startsWith("Meat")) {
      pos++;
    }
    KoLCharacter.setAvailableMeat(StringUtilities.parseLong(tokens[++pos]));

    // Determine the player's ascension count, if any.
    // This is seen by whether or not the word "Ascensions"
    // appears in their player profile.

    if (responseText.contains("Ascensions:")) {
      while (!tokens[pos].startsWith("Ascensions")) {
        pos++;
      }
      KoLCharacter.setAscensions(StringUtilities.parseInt(tokens[++pos]));
    }

    // There may also be a "turns this run" field which
    // allows you to have a Ronin countdown.
    boolean runStats = responseText.contains("(this run)");

    while (!tokens[pos].startsWith("Turns") || (runStats && !tokens[pos].contains("(this run)"))) {
      pos++;
    }

    KoLCharacter.setCurrentRun(StringUtilities.parseInt(tokens[++pos]));
    while (!tokens[pos].startsWith("Days") || (runStats && !tokens[pos].contains("(this run)"))) {
      pos++;
    }

    KoLCharacter.setCurrentDays(StringUtilities.parseInt(tokens[++pos]));

    // Determine the player's zodiac sign, if any. We
    // could read the path in next, but it's easier to
    // read it from the full response text.

    if (responseText.contains("Sign:")) {
      while (!tokens[pos].startsWith("Sign:")) {
        pos++;
      }
      KoLCharacter.setSign(tokens[++pos]);
    }

    // This is where Path: optionally appears
    KoLCharacter.setRestricted(responseText.contains("standard.php"));

    // Consumption restrictions have special messages.
    if (responseText.contains("You may not eat or drink anything.")) {
      KoLCharacter.setPath(Path.OXYGENARIAN);
    } else if (responseText.contains(
        "You may not eat any food or drink any non-alcoholic beverages.")) {
      KoLCharacter.setPath(Path.BOOZETAFARIAN);
    } else if (responseText.contains("You may not consume any alcohol.")) {
      KoLCharacter.setPath(Path.TEETOTALER);
    }

    // You are in Hardcore mode, and may not receive items or buffs
    // from other players.

    boolean hardcore = responseText.contains("You are in Hardcore mode");
    KoLCharacter.setHardcore(hardcore);

    // You may not receive items from other players until you have
    // played # more Adventures.

    KoLCharacter.setRonin(responseText.contains("You may not receive items from other players"));

    // Deduce interaction from above settings

    CharPaneRequest.setInteraction();

    // See if the player has a store
    KoLCharacter.setStore(responseText.contains("Mall of Loathing"));

    // See if the player has a display case
    KoLCharacter.setDisplayCase(responseText.contains("in the Museum"));

    while (!tokens[pos].startsWith("Skill")) {
      pos++;
    }

    List<UseSkillRequest> newSkillSet = new ArrayList<>();
    List<UseSkillRequest> permedSkillSet = new ArrayList<>();
    Set<Integer> hardcorePermedSkillSet = new HashSet<>();

    // Available skills added to newSkillSet and also have perm status saved
    // Unavailable skills not added to newSkillSet but have perm status saved
    parseAndUpdateSkills(doc, newSkillSet, permedSkillSet, hardcorePermedSkillSet);

    // The Smile of Mr. A no longer appears on the char sheet
    if (Preferences.getInteger("goldenMrAccessories") > 0) {
      UseSkillRequest skill = UseSkillRequest.getUnmodifiedInstance(SkillPool.SMILE_OF_MR_A);
      newSkillSet.add(skill);
    }

    // Toggle Optimality does not appear on the char sheet
    if (Preferences.getInteger("skillLevel7254") > 0) {
      UseSkillRequest skill = UseSkillRequest.getUnmodifiedInstance(SkillPool.TOGGLE_OPTIMALITY);
      newSkillSet.add(skill);
    }

    // If you have the Cowrruption effect, you can Absorb Cowrruption if a Cow Puncher
    if (KoLConstants.activeEffects.contains(EffectPool.get(EffectPool.COWRRUPTION))
        && KoLCharacter.getAscensionClass() == AscensionClass.COW_PUNCHER) {
      UseSkillRequest skill = UseSkillRequest.getUnmodifiedInstance(SkillPool.ABSORB_COWRRUPTION);
      newSkillSet.add(skill);
    }

    // If you have looked at your Bird-a-Day calendar today, you can use
    // "Seek out a Bird".
    if (Preferences.getBoolean("_canSeekBirds")) {
      UseSkillRequest skill = UseSkillRequest.getUnmodifiedInstance(SkillPool.SEEK_OUT_A_BIRD);
      newSkillSet.add(skill);
    }

    // Set the skills that we saw
    KoLCharacter.setAvailableSkills(newSkillSet);
    KoLCharacter.setPermedSkills(permedSkillSet);
    KoLCharacter.setHardcorePermedSkills(hardcorePermedSkillSet);

    // Update modifiers.
    KoLCharacter.recalculateAdjustments();

    // Update uneffect methods and heal amounts for updated skills
    UneffectRequest.reset();
    HPRestoreItemList.updateHealthRestored();

    // Grey You path has absorptions
    if (KoLCharacter.inGreyYou()) {
      GreyYouManager.parseAbsorptions(responseText);
    }

    // Set the character's avatar.
    CharSheetRequest.parseAvatar(responseText);
  }

  private static final Pattern AVATAR_PATTERN =
      Pattern.compile(
          "<img src=[^>]*?(?:cloudfront.net|images.kingdomofloathing.com|/images)/([^>'\"\\s]+)");

  public static void parseAvatar(final String responseText) {
    // You, Robot has an Avatar consisting of five overlaid .png files
    if (KoLCharacter.inRobocore()) {
      YouRobotManager.parseAvatar(responseText);
      return;
    }

    Matcher avatarMatcher = CharSheetRequest.AVATAR_PATTERN.matcher(responseText);
    if (avatarMatcher.find()) {
      KoLCharacter.setAvatar(avatarMatcher.group(1));
    }
  }

  /**
   * Helper method used to find the statistic points. This method was created because
   * statistic-point finding is exactly the same for every statistic point.
   *
   * @param tokens The array containing the tokens to be parsed
   * @param pos The current position in the array
   * @param searchString The search string indicating the beginning of the statistic
   * @return The 3-element array containing the parsed statistics and the next position
   */
  private static long[] findStatPoints(final String[] tokens, int pos, final String searchString) {
    long[] stats = new long[3];

    while (!tokens[pos].startsWith(searchString)) {
      pos++;
    }

    stats[0] = StringUtilities.parseInt(tokens[++pos]);
    int base = (int) CharSheetRequest.retrieveBase(tokens[++pos], (int) stats[0]);

    int subPoints = 0;

    // Grey Goo / Zootomist classes do not have stat subpoints
    var cls = KoLCharacter.getAscensionClass();
    if (cls != AscensionClass.GREY_GOO && cls != AscensionClass.ZOOTOMIST) {
      while (!tokens[pos].startsWith("(")) {
        pos++;
      }
      subPoints = StringUtilities.parseInt(tokens[++pos]);
    }

    stats[1] = KoLCharacter.calculateSubpoints(base, subPoints);

    // If we've advanced straight to the next stat (probaby because of Grey You), roll back one
    if (tokens[pos].startsWith("M")) {
      pos--;
    }

    stats[2] = pos;

    return stats;
  }

  /**
   * Utility method for retrieving the base value for a statistic, given the tokenizer, and assuming
   * that the base might be located in the next token. If it isn't, the default value is returned
   * instead.
   *
   * @param token The string possibly containing the base value
   * @param defaultBase The value to return, if no base value is found
   * @return The parsed base value, or the default value if no base value is found
   */
  private static long retrieveBase(final String token, final long defaultBase) {
    Matcher baseMatcher = CharSheetRequest.BASE_PATTERN.matcher(token);
    return baseMatcher.find() ? StringUtilities.parseLong(baseMatcher.group(1)) : defaultBase;
  }

  /**
   * Represents the ID, name, and perm status of a single skill parsed from charsheet.php There is
   * no guarantee that the skill ID, name, or perm status is actually valid.
   */
  public static class ParsedSkillInfo {
    public enum PermStatus {
      NONE,
      SOFTCORE,
      HARDCORE,
    }

    /** Skill ID. A negative value indicates that the skill ID could not be parsed. */
    public final int id;

    public final String name;
    public final PermStatus permStatus;

    ParsedSkillInfo(int id, String name, PermStatus permStatus) {
      this.id = id;
      this.name = name;
      this.permStatus = permStatus;
    }

    ParsedSkillInfo(String name, PermStatus permStatus) {
      this(-1, name, permStatus);
    }

    /**
     * @return Whether the skill ID is bad (could not be parsed) and shouldn't be used.
     */
    boolean isBadId() {
      return this.id < 0;
    }

    @Override
    public boolean equals(Object o) {
      if (o == this) {
        return true;
      }

      if (!(o instanceof ParsedSkillInfo other)) {
        return false;
      }

      return id == other.id && name.equals(other.name) && permStatus == other.permStatus;
    }

    @Override
    public String toString() {
      return String.format(
          "ParsedSkillInfo(id=%d, name=%s, permStatus=%s)", this.id, this.name, this.permStatus);
    }
  }

  private static void parseAndUpdateSkills(
      Document doc,
      List<UseSkillRequest> available,
      List<UseSkillRequest> permed,
      Set<Integer> hardcore) {
    updateSkillSets(parseSkills(doc, true), available, permed, hardcore);
    updateSkillSets(parseSkills(doc, false), null, permed, hardcore);
  }

  public static void updateSkillSets(
      List<ParsedSkillInfo> parsedSkillInfos,
      List<UseSkillRequest> available,
      List<UseSkillRequest> permed,
      Set<Integer> hardcore) {
    for (ParsedSkillInfo skillInfo : parsedSkillInfos) {
      UseSkillRequest currentSkill = null;
      if (skillInfo.isBadId()) {
        System.err.println(
            "Cannot parse skill ID in 'onclick' attribute for skill: " + skillInfo.name);
      } else {
        currentSkill = UseSkillRequest.getUnmodifiedInstance(skillInfo.id);
        if (currentSkill == null) {
          System.err.println("Unknown skill ID in charsheet.php: " + skillInfo.id);
        }
      }

      // Cannot find skill by ID, fall back to skill name check
      if (currentSkill == null) {
        currentSkill = UseSkillRequest.getUnmodifiedInstance(skillInfo.name);
        if (currentSkill == null) {
          System.err.println("Ignoring unknown skill name in charsheet.php: " + skillInfo.name);
          continue;
        }
      }

      boolean shouldAddSkill = true;
      int skillId = currentSkill.getSkillId();

      if (SkillDatabase.isBookshelfSkill(skillId)) {
        shouldAddSkill =
            (!KoLCharacter.inBadMoon() && !KoLCharacter.inAxecore())
                || KoLCharacter.kingLiberated();
      }

      switch (skillId) {
        case SkillPool.OLFACTION -> {
          shouldAddSkill =
              (!KoLCharacter.inBadMoon() && !KoLCharacter.inAxecore())
                  || KoLCharacter.skillsRecalled();
        }
        case SkillPool.CRYPTOBOTANIST, SkillPool.INSECTOLOGIST, SkillPool.PSYCHOGEOLOGIST -> {
          Preferences.setString("currentSITSkill", currentSkill.getSkillName());
        }
      }

      if (shouldAddSkill && available != null) {
        available.add(currentSkill);
      }

      if (skillInfo.permStatus == ParsedSkillInfo.PermStatus.SOFTCORE) {
        permed.add(currentSkill);
      }
      if (skillInfo.permStatus == ParsedSkillInfo.PermStatus.HARDCORE) {
        permed.add(currentSkill);
        hardcore.add(currentSkill.getSkillId());
      }
    }
  }

  /**
   * Parses skill information from charsheet.php.
   *
   * @param doc Parsed-and-cleaned HTML document
   */

  // Assumption:
  // In the cleaned-up HTML, each skill is displayed as an <a> tag that looks like any of the
  // following:
  //
  //	(Most of the time)
  //	<a
  // onclick="javascript:poop(&quot;desc_skill.php?whichskill=SKILL_ID&amp;self=true&quot;,&quot;skill&quot;, 350, 300)">Skill Name</a><br>
  //	<a
  // onclick="javascript:poop(&quot;desc_skill.php?whichskill=SKILL_ID&amp;self=true&quot;,&quot;skill&quot;, 350, 300)">Skill Name</a> (P)<br>
  //	<a
  // onclick="javascript:poop(&quot;desc_skill.php?whichskill=SKILL_ID&amp;self=true&quot;,&quot;skill&quot;, 350, 300)">Skill Name</a> (<b>HP</b>)<br>
  //	(Rarely)
  //	<a onclick="skill(SKILL_ID)">Skill Name</a>
  //
  // ...where SKILL_ID is an integer, and P/HP indicate perm status.
  //
  // Skills that you cannot use right now (e.g. due to path restrictions) are wrapped in a <span>
  // tag:
  //
  //	<span id="permskills" ...>...</span>

  private static final String AVAILABLE_SKILL_XPATH =
      "//a[contains(@onclick,'skill') and not(ancestor::*[@id='permskills'])]";

  private static final String UNAVAILABLE_SKILL_XPATH =
      "//a[contains(@onclick,'skill') and (ancestor::*[@id='permskills'])]";

  private static List<ParsedSkillInfo> parseSkills(Document doc) {
    List<ParsedSkillInfo> retval = new ArrayList<>();
    retval.addAll(parseSkills(doc, true));
    retval.addAll(parseSkills(doc, false));
    return retval;
  }

  private static List<ParsedSkillInfo> parseSkills(Document doc, boolean available) {
    String xpath = available ? AVAILABLE_SKILL_XPATH : UNAVAILABLE_SKILL_XPATH;
    NodeList skillNodes;
    try {
      skillNodes =
          (NodeList)
              XPathFactory.newInstance().newXPath().evaluate(xpath, doc, XPathConstants.NODESET);
    } catch (XPathExpressionException e) {
      // Our xpath selector is bad; this build shouldn't be released at all
      e.printStackTrace();
      throw new RuntimeException("Bad XPath selector");
    }

    List<ParsedSkillInfo> parsedSkillInfos = new ArrayList<>();

    Matcher[] onclickSkillIdMatchers = {
      Pattern.compile("\\bwhichskill=(\\d+)").matcher(""),
      Pattern.compile("\\bskill\\((\\d+)\\)").matcher(""),
    };
    for (int i = 0; i < skillNodes.getLength(); ++i) {
      Node node = skillNodes.item(i);

      boolean isSkillIdFound = false;
      int skillId = -1;
      String skillName = node.getTextContent();
      ParsedSkillInfo.PermStatus permStatus = ParsedSkillInfo.PermStatus.NONE;

      // Parse following sibling nodes to check perm status
      Node nextSibling = node.getNextSibling();
      while (nextSibling != null) {
        if (nextSibling.getNodeType() == Node.TEXT_NODE) {
          String siblingText = nextSibling.getTextContent();
          if (siblingText.contains("(HP)")) {
            permStatus = ParsedSkillInfo.PermStatus.HARDCORE;
          } else if (siblingText.contains("(P)")) {
            permStatus = ParsedSkillInfo.PermStatus.SOFTCORE;
          }

          // If the text node does not contain perm status, keep examining subsequent siblings
        } else if (nextSibling.getNodeName().equals("b")) {
          String siblingText = nextSibling.getTextContent();
          if (siblingText.contains("HP")) {
            permStatus = ParsedSkillInfo.PermStatus.HARDCORE;
          } else if (siblingText.contains("P")) {
            permStatus = ParsedSkillInfo.PermStatus.SOFTCORE;
          }

          // If a <b></b> tag is encountered, stop examining siblings regardless of what
          // the tag contains
          break;
        } else {
          // If any other node is encountered, stop examining siblings
          break;
        }

        nextSibling = nextSibling.getNextSibling();
      }

      String onclick = node.getAttributes().getNamedItem("onclick").getNodeValue();

      // Find the first successful matcher
      for (Matcher skillIdMatcher : onclickSkillIdMatchers) {
        skillIdMatcher.reset(onclick);
        if (skillIdMatcher.find()) {
          isSkillIdFound = true;
          skillId = StringUtilities.parseInt(skillIdMatcher.group(1));
          // KoL bug - Summon Hilarious Objects has skillId 17 in charsheet
          if ((skillId == 17) && skillName.equals("Summon Hilarious Objects")) {
            skillId = 7226;
          }
          break;
        }
      }

      if (isSkillIdFound) {
        parsedSkillInfos.add(new ParsedSkillInfo(skillId, skillName, permStatus));
      } else {
        parsedSkillInfos.add(new ParsedSkillInfo(skillName, permStatus));
      }
    }

    return parsedSkillInfos;
  }

  // parseStatus creates a Document from the HTML responseText and calls
  // versions of the following methods that accept such.
  //
  // The following methods that accept a responseText are for use by tests.

  public static void parseAndUpdateSkills(
      String responseText,
      List<UseSkillRequest> available,
      List<UseSkillRequest> permed,
      Set<Integer> hardcore) {
    try {
      Document doc = domSerializer.createDOM(cleaner.clean(responseText));
      parseAndUpdateSkills(doc, available, permed, hardcore);
    } catch (ParserConfigurationException e) {
      e.printStackTrace();
    }
  }

  public static List<ParsedSkillInfo> parseSkills(final String responseText) {
    try {
      Document doc = domSerializer.createDOM(cleaner.clean(responseText));
      return parseSkills(doc);
    } catch (ParserConfigurationException e) {
      e.printStackTrace();
      return new ArrayList<>();
    }
  }

  public static List<ParsedSkillInfo> parseSkills(final String responseText, boolean available) {
    try {
      Document doc = domSerializer.createDOM(cleaner.clean(responseText));
      return parseSkills(doc, available);
    } catch (ParserConfigurationException e) {
      e.printStackTrace();
      return new ArrayList<ParsedSkillInfo>();
    }
  }

  public static void parseStatus(final JSONObject json) throws JSONException {
    int muscle = json.getIntValue("muscle");
    int mysticality = json.getIntValue("mysticality");
    int moxie = json.getIntValue("moxie");
    long rawmuscle;
    long rawmysticality;
    long rawmoxie;
    if (KoLCharacter.inGreyYou() || KoLCharacter.inZootomist()) {
      // Raw values are more precise, but they don't exist in Grey You and are wrong in Zooto
      long basemuscle = json.getLong("basemuscle");
      rawmuscle = basemuscle * basemuscle;

      long basemysticality = json.getLong("basemysticality");
      rawmysticality = basemysticality * basemysticality;

      long basemoxie = json.getLong("basemoxie");
      rawmoxie = basemoxie * basemoxie;
    } else {
      rawmuscle = json.getLong("rawmuscle");
      rawmysticality = json.getLong("rawmysticality");
      rawmoxie = json.getLong("rawmoxie");
    }

    KoLCharacter.setStatPoints(muscle, rawmuscle, mysticality, rawmysticality, moxie, rawmoxie);
    int level = json.getIntValue("level");
    KoLCharacter.setLevel(level);
  }
}
