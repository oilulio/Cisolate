/*
Copyright (C) 2016  S Combes

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.

*/
package cisolate;

public abstract class Seeker {

// The base class for a candidate solution to an optimisation problem.
// Requires the solution to an optimisation problem to be a class 
// capable of proposing a small perturbation of itself (neighbour()) and
// a set of 'nearest' neighbours (neighbourhood()).


public abstract Seeker neighbour();  
// A small perturbation in the possible solution.
// Can be determined stochastically (e.g. random move, random direction) or
// deterministically (e.g. fixed cycle through possible values), but
// this latter only works in one dimension and with a suitable optimiser caller.

public abstract Seeker[] neighbourhood(int n); 
// Returns up to n neighbours (size of returned array is <=n).
// Called with n=0, routine should maximise return (within its own limits).
 
// Where possible, an exhaustive list of the neighbours, e.g. in a grid
// the solution could be the N,NE,E,SE,...NW points.
// Allowed to be a finite subset of these : in the limit can be a single, 
// random point - i.e. neighbour() itself in the list.

// Only really makes sense to use a subset when there is a large choice.
// A subset makes calling routine a stochastic optimisation : returning complete
// set allows a deterministic optimisation (subject to caller's method).

public abstract Seeker deepCopy();  

public abstract String toString();      

}