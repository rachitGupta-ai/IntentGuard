"""Alert Triage Sidecar.

An advisory, read-mostly Python/LangGraph service that automates the
investigative first pass on high-risk alerts raised by the authoritative
IntentGuard semantic-firewall engine. The sidecar never blocks, allows,
enforces, or mutates protected state.

Package layout
--------------
- ``sidecar.config``   : central configuration module (tunables + validated bounds)
- ``sidecar.models``   : Pydantic data models (envelope, state, verdict, report, ...)
- ``sidecar.contract`` : versioned Integration_Contract adapter
- ``sidecar.triage``   : the Investigation_Graph nodes and orchestration
- ``sidecar.tools``    : the read-only Read_Tool layer
- ``sidecar.hitl``     : checkpoint / human-in-the-loop manager
- ``sidecar.app``      : top-level end-to-end assembly (the ``Sidecar`` app)
"""

from sidecar.app import (
    DecisionResult,
    InvestigatingAdmitter,
    Sidecar,
    TriggerClassifierAdapter,
)

__all__ = [
    "__version__",
    # top-level application assembly (task 20.3)
    "Sidecar",
    "TriggerClassifierAdapter",
    "InvestigatingAdmitter",
    "DecisionResult",
]

__version__ = "0.1.0"
