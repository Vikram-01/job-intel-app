from ..netsafe import SafeClient
from .ashby import AshbyConnector
from .base import Connector, RawJob
from .greenhouse import GreenhouseConnector
from .lever import LeverConnector
from .smartrecruiters import SmartRecruitersConnector

CONNECTORS: dict[str, type[Connector]] = {
    c.ats: c for c in (GreenhouseConnector, LeverConnector, AshbyConnector, SmartRecruitersConnector)
}


def make_connector(ats: str, client: SafeClient) -> Connector:
    try:
        return CONNECTORS[ats](client)
    except KeyError as exc:
        raise ValueError(f"unsupported ATS: {ats}") from exc


__all__ = ["CONNECTORS", "Connector", "RawJob", "make_connector"]
