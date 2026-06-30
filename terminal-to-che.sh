#!/bin/bash

pattern="${1:-workspace}"

kubectl exec -it $(kubectl get pod | grep -o "${pattern}\S\+" | head -n 1) -- bash
