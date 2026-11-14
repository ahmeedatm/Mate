-- Mate pour Cyberpunk 2077 : écran cyberware, en lecture seule (rien n'est posé ni retiré). Bloc lent cyberware.json :
-- corps de V, capacité, emplacements par zone (verrouillés compris), implants posés et possédés. Chargé par logic.lua.
-- Contrat : docs/specs/2026-11-14-cyberpunk-cyberware-design.md.
return function(h)
  local try, itemInfo = h.try, h.itemInfo
  local M = {}

  local AREAS = { "SystemReplacementCW", "FrontalCortexCW", "EyesCW", "ArmsCW", "HandsCW", "MusculoskeletalSystemCW",
    "NervousSystemCW", "CardiovascularSystemCW", "ImmuneSystemCW", "IntegumentarySystemCW", "LegsCW" }
  local IS_AREA = {}
  for _, a in ipairs(AREAS) do IS_AREA[a] = true end

  -- « gamedataEquipmentArea : EyesCW (12) » → « EyesCW ».
  local function word(e)
    local s = tostring(e)
    return string.match(s, ":%s*(%S+)") or s
  end

  local function es() return Game.GetScriptableSystemsContainer():Get(CName.new("EquipmentSystem")) end

  local function seed(id)
    local ok, s = pcall(function() return id.rng_seed end)
    if not ok or s == nil then ok, s = pcall(function() return id.rngSeed end) end
    return ok and tonumber(s) or 0
  end

  -- Clé d'un implant : id TweakDB et graine (deux exemplaires du même implant peuvent différer).
  local function key(id) return TDBID.ToStringDEBUG(ItemID.GetTDBID(id)) .. "#" .. tostring(seed(id)) end

  local function valid(id) return id ~= nil and ItemID.IsValid(id) end

  -- Zone d'un objet ; nil pour ce qui ne s'équipe pas (la plupart de l'inventaire), sans erreur.
  local function areaOf(id)
    local rec = TweakDBInterface.GetItemRecord(ItemID.GetTDBID(id))
    local ea = rec and rec:EquipArea()
    return ea and word(ea:Type()) or nil
  end

  -- Coût en capacité : exigence HumanityAvailable de l'implant, comme GetItemCapacityRequirement du jeu.
  local function cost(pl, id)
    local data = Game.GetTransactionSystem():GetItemData(pl, id)
    local reqs = RPGManager.GetEquipRequirements(pl, data)
    for i = 1, #(reqs or {}) do
      if word(reqs[i].statType) == "HumanityAvailable" then return tonumber(reqs[i].requiredValue) or 0 end
    end
    return 0
  end

  local function stat(pl, name) return Game.GetStatsSystem():GetStatValue(pl:GetEntityID(), gamedataStatType[name]) end

  local function describe(pl, id, k)
    local info = itemInfo(pl, id)
    local rec = TweakDBInterface.GetItemRecord(ItemID.GetTDBID(id))
    local r = { key = key(id), tdb = info.tdb, name = info.name, quality = info.quality }
    r.cost = try(k .. ".cost", function() return cost(pl, id) end)
    -- Icône : le nom de la pièce dans les atlas item_icons du jeu (IconPath = pièce de UIIcon.<nom>, vu au spike).
    r.icon = try(k .. ".icon", function() return rec:IconPath() end)
    return r
  end

  -- Slot de la zone, lu comme la page Perso (GetItemInEquipSlot prouvé au premier spike).
  local function inSlot(pd, area, i) return pd:GetItemInEquipSlot(area, i) end

  function M.block(pl)
    local pd = try("cw.pd", function() return es():GetPlayerData(pl) end)
    if not pd then return nil end
    local zones, equipped = {}, {}
    for _, a in ipairs(AREAS) do
      local area = gamedataEquipmentArea[a]
      -- `true` : avec les emplacements verrouillés (perk ou attribut manquant), affichés comme dans le jeu.
      local n = try("cw.n." .. a, function() return pd:GetNumberOfSlots(area, true) end) or 0
      local slots = {}
      for i = 0, n - 1 do
        local s = { locked = try("cw.lock." .. a .. i, function() return pd:IsSlotLocked(area, i) end) == true }
        local id = try("cw.id." .. a .. i, function() return inSlot(pd, area, i) end)
        if valid(id) then
          s.item = try("cw.item." .. a .. i, function() return describe(pl, id, "cw." .. a .. i) end)
          if s.item then equipped[s.item.key] = true end
        end
        slots[#slots + 1] = s
      end
      zones[a] = slots
    end
    local owned = {}
    local _, list = Game.GetTransactionSystem():GetItemList(pl)
    for i = 1, #(list or {}) do
      local id = list[i]:GetID()
      local a = try("cw.own." .. i, function() return areaOf(id) end)
      if a and IS_AREA[a] and not equipped[key(id)] then
        local d = try("cw.owned." .. i, function() return describe(pl, id, "cw.owned." .. i) end)
        if d then d.area = a; owned[#owned + 1] = d end
      end
    end
    local gender = try("cw.gender", function() return tostring(pl:GetResolvedGenderName()) end)
    return {
      v = 1,
      body = gender and string.find(gender, "Female") and "woman" or "man",
      capacity = {
        free = try("cw.free", function() return stat(pl, "HumanityAvailable") end),
        used = try("cw.used", function() return stat(pl, "HumanityAllocated") end),
        max = try("cw.max", function() return stat(pl, "HumanityTotalMaxValue") end),
      },
      zones = zones,
      owned = owned,
    }
  end

  return M
end
