-- Bus profile for OSRM, built as an overlay on the car.lua shipped with the same image.
-- Only bus-specific differences are defined here: upstream car.lua changes are inherited.

local car = require('car')

api_version = 4

-- -- helpers
local function remove_from_set(set, ...)
  for _, v in ipairs({...}) do set[v] = nil end
end

local function add_to_set(set, ...)
  for _, v in ipairs({...}) do set[v] = true end
end
-- --

local function setup()
  local profile = car.setup()

  -- -- vehicle (articulated urban bus, worst case of the fleet)
  profile.vehicle_height    = 3.5    -- m, 3.48 with roof pantograph, rounded up
  profile.vehicle_width     = 2.55   -- m
  profile.vehicle_length    = 18.0   -- m
  profile.vehicle_weight    = 30000  -- kg, GVW, rounded up
  profile.vehicle_max_speed = 100    -- km/h, IT: 100 motorway / 80 extra-urban (art. 142 CdS)
  -- --

  -- -- access: bus is psv / motor_vehicle / vehicle, not motorcar
  profile.access_tags_hierarchy = Sequence { 'bus', 'psv', 'motor_vehicle', 'vehicle', 'access' }
  profile.restrictions          = Sequence { 'bus', 'psv', 'motor_vehicle', 'vehicle' }
  remove_from_set(profile.access_tag_whitelist, 'motorcar')
  add_to_set(profile.access_tag_whitelist, 'psv', 'bus')
  remove_from_set(profile.access_tag_blacklist, 'psv', 'bus')
  add_to_set(profile.access_tag_blacklist, 'motorcar')
  -- --

  -- -- bus-only infrastructure
  profile.speeds.highway.busway = 40  -- km/h, estimated urban speed
  add_to_set(profile.restricted_highway_whitelist, 'busway')
  add_to_set(profile.barrier_whitelist, 'bus_trap')
  remove_from_set(profile.avoid, 'hov_lanes')
  -- --

  return profile
end

return {
  setup        = setup,
  process_way  = car.process_way,
  process_node = car.process_node,
  process_turn = car.process_turn
}
