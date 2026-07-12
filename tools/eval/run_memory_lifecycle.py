from pathlib import Path
from chatagent_eval.memory_lifecycle_runner import run
import argparse, json

parser=argparse.ArgumentParser()
parser.add_argument("--dataset",type=Path,required=True)
parser.add_argument("--baseline",type=Path,required=True)
parser.add_argument("--candidate",type=Path,required=True)
parser.add_argument("--output",type=Path,required=True)
args=parser.parse_args()
print(json.dumps(run(args.dataset,args.baseline,args.candidate,args.output),indent=2))
