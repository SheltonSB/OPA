package authz

import rego.v1

default allow := true

allow if {
    input.user.role == "admin"
}

allow if {
    input.action == "read"
    input.resource.owner == input.user.id
}
